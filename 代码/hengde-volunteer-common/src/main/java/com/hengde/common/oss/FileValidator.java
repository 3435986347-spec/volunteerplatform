package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 上传文件校验：扩展名 + 大小 + <b>文件真实内容（魔数）</b>，并提供服务端推导的 MIME。
 *
 * <p>{@link AliyunOssFileStorageService#upload} / {@link VolcTosFileStorageService#upload} 入口已用
 * {@link OssProperties} 配置做基线校验；调用方若要按场景收紧（如头像仅允许图片），可在上传前自行调
 * {@code FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, maxSize)}。</p>
 *
 * <p><b>为什么要校验魔数、并自行推导 Content-Type：</b>扩展名和 multipart 请求里的 Content-Type
 * 都是客户端可任意伪造的。若只看扩展名、又把客户端传来的 Content-Type 原样写进对象存储，
 * 攻击者就能上传一个名为 {@code evil.jpg}、Content-Type 为 {@code text/html}、内容是脚本的文件；
 * 对象存储会以 {@code text/html} 回源，浏览器访问该 URL 时直接渲染执行 —— 即桶域名下的存储型 XSS。
 * 因此这里：① 读文件头比对魔数，确认内容与扩展名相符；② MIME 一律由扩展名推导，
 * 未收录的扩展名回落到 {@code application/octet-stream}（浏览器只下载、不渲染）。</p>
 *
 * @author hengde
 */
public final class FileValidator {

    /** 图片类扩展名，供「仅允许图片」的场景（头像、轮播图、签名等）使用 */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 读取用于魔数比对的文件头长度：webp 需看到偏移 8 起的 4 字节，16 字节足以覆盖全部已支持类型 */
    private static final int HEADER_LENGTH = 16;

    /** 兜底 MIME：未收录的扩展名一律按二进制流，浏览器只会下载不会渲染 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** 扩展名 → 服务端推导的 MIME；<b>绝不采信客户端传来的 Content-Type</b> */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /** 扩展名 → 文件头特征匹配规则；未收录的扩展名跳过魔数校验（仍受扩展名白名单约束） */
    private static final Map<String, Predicate<byte[]>> MAGIC = Map.ofEntries(
            Map.entry("jpg", h -> startsWith(h, 0, 0xFF, 0xD8, 0xFF)),
            Map.entry("jpeg", h -> startsWith(h, 0, 0xFF, 0xD8, 0xFF)),
            Map.entry("png", h -> startsWith(h, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            Map.entry("gif", h -> startsWith(h, 0, 0x47, 0x49, 0x46, 0x38)),
            Map.entry("bmp", h -> startsWith(h, 0, 0x42, 0x4D)),
            // webp = RIFF....WEBP，需同时命中偏移 0 与偏移 8 两段
            Map.entry("webp", h -> startsWith(h, 0, 0x52, 0x49, 0x46, 0x46)
                    && startsWith(h, 8, 0x57, 0x45, 0x42, 0x50)),
            Map.entry("pdf", h -> startsWith(h, 0, 0x25, 0x50, 0x44, 0x46)),
            Map.entry("doc", FileValidator::isOle2),
            Map.entry("xls", FileValidator::isOle2),
            Map.entry("docx", FileValidator::isZip),
            Map.entry("xlsx", FileValidator::isZip));

    private FileValidator() {
    }

    /**
     * 校验上传文件，不合规抛 {@link BusinessException}（400）。
     *
     * <p>依次校验：非空 → 大小 → 扩展名白名单 → 文件头魔数与扩展名相符。</p>
     *
     * @param file              上传文件
     * @param allowedExtensions 允许的扩展名集合（小写，不含点）
     * @param maxSize           大小上限（字节）
     */
    public static void validate(MultipartFile file, Set<String> allowedExtensions, long maxSize) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException("文件大小超过限制（最大 " + (maxSize / 1024 / 1024) + "MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!StringUtils.hasText(ext) || !allowedExtensions.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + (StringUtils.hasText(ext) ? ext : "未知"));
        }
        validateContent(file, ext);
    }

    /**
     * 取文件扩展名（小写、不含点）。
     *
     * @param filename 文件名
     * @return 扩展名；无扩展名返回空串
     */
    public static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 按扩展名推导 MIME，供写入对象存储时使用。
     *
     * <p>刻意<b>不</b>读取 {@code MultipartFile.getContentType()}——那个值来自客户端请求头，可伪造。</p>
     *
     * @param filename 文件名
     * @return MIME 类型；未收录的扩展名返回 {@code application/octet-stream}
     */
    public static String contentTypeOf(String filename) {
        return CONTENT_TYPES.getOrDefault(extensionOf(filename), DEFAULT_CONTENT_TYPE);
    }

    /**
     * 校验文件真实内容与扩展名相符（读文件头比对魔数）。
     *
     * <p>拦的是「把任意内容改成 .jpg 后缀上传」这类绕过。注意 docx/xlsx 本质是 zip 包、
     * doc/xls 是 OLE2 复合文档，这里只能校验到容器格式层面，无法保证内部确为 Office 文档；
     * 图片类则可精确到具体编码格式。</p>
     */
    private static void validateContent(MultipartFile file, String ext) {
        Predicate<byte[]> rule = MAGIC.get(ext);
        if (rule == null) {
            return;
        }
        if (!rule.test(readHeader(file))) {
            throw new BusinessException("文件内容与扩展名不符，疑似伪造：" + ext);
        }
    }

    /** 读取文件头若干字节；不足 {@link #HEADER_LENGTH} 时按实际长度返回 */
    private static byte[] readHeader(MultipartFile file) {
        byte[] buffer = new byte[HEADER_LENGTH];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(buffer, 0, HEADER_LENGTH);
            return read == HEADER_LENGTH ? buffer : Arrays.copyOf(buffer, Math.max(read, 0));
        } catch (IOException e) {
            throw new BusinessException("上传文件读取失败");
        }
    }

    /** 比对 {@code header} 自 {@code offset} 起是否为给定字节序列 */
    private static boolean startsWith(byte[] header, int offset, int... expected) {
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((header[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** OLE2 复合文档头（doc / xls 等 Office 97-2003 格式） */
    private static boolean isOle2(byte[] header) {
        return startsWith(header, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    }

    /** ZIP 头（docx / xlsx 等 OOXML 实为 zip 包；空包与分卷包变体一并接受） */
    private static boolean isZip(byte[] header) {
        return startsWith(header, 0, 0x50, 0x4B, 0x03, 0x04)
                || startsWith(header, 0, 0x50, 0x4B, 0x05, 0x06)
                || startsWith(header, 0, 0x50, 0x4B, 0x07, 0x08);
    }
}
