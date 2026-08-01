package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FileValidator 验证：扩展名/大小基线校验 + 文件头魔数 + 服务端 MIME 推导。纯静态工具，无需 Spring/Docker。
 *
 * <p><b>这些用例锁的是一条安全边界，不要因为「看起来多余」就删掉：</b>扩展名与 multipart 的
 * Content-Type 都由客户端控制。若只看扩展名、又把客户端 Content-Type 原样写进对象存储，
 * 攻击者可上传名为 {@code evil.jpg}、Content-Type 为 {@code text/html}、内容是脚本的文件，
 * 对象存储会以 text/html 回源，浏览器访问即执行 —— 桶域名下的存储型 XSS。
 * {@code htmlDisguisedAsJpg_rejected} 与 {@code contentTypeOf_*} 两组就是防这个的回归网。</p>
 *
 * @author hengde
 */
class FileValidatorTest {

    private static final long MAX = 10 * 1024 * 1024L;
    private static final Set<String> DOC_EXTENSIONS = Set.of("pdf", "doc", "docx", "xls", "xlsx");

    // ---------- 真实文件放行 ----------

    @Test
    void validate_realImages_passed() {
        assertDoesNotThrow(() -> validateAsImage(png(), "a.png"));
        assertDoesNotThrow(() -> validateAsImage(jpeg(), "a.jpg"));
        assertDoesNotThrow(() -> validateAsImage(jpeg(), "a.jpeg"));
        assertDoesNotThrow(() -> validateAsImage(gif(), "a.gif"));
        assertDoesNotThrow(() -> validateAsImage(webp(), "a.webp"));
        assertDoesNotThrow(() -> validateAsImage(bmp(), "a.bmp"));
    }

    @Test
    void validate_realDocuments_passed() {
        assertDoesNotThrow(() -> FileValidator.validate(file(pdf(), "a.pdf"), DOC_EXTENSIONS, MAX));
        // docx/xlsx 本质是 zip 包，只能校验到容器格式
        assertDoesNotThrow(() -> FileValidator.validate(file(zip(), "a.docx"), DOC_EXTENSIONS, MAX));
        assertDoesNotThrow(() -> FileValidator.validate(file(ole2(), "a.doc"), DOC_EXTENSIONS, MAX));
    }

    // ---------- 魔数：内容与扩展名不符一律拒 ----------

    /** 核心攻击场景：脚本内容改名成图片后缀上传，修复前会被存成 text/html 并可执行 */
    @Test
    void validate_htmlDisguisedAsJpg_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> validateAsImage(html(), "evil.jpg"));
        assertEquals("文件内容与扩展名不符，疑似伪造：jpg", e.getMessage());
    }

    @Test
    void validate_contentExtensionMismatch_rejected() {
        assertThrows(BusinessException.class, () -> validateAsImage(html(), "evil.png"));
        assertThrows(BusinessException.class, () -> validateAsImage(png(), "b.jpg"));
        assertThrows(BusinessException.class, () -> validateAsImage(pdf(), "c.png"));
        assertThrows(BusinessException.class,
                () -> FileValidator.validate(file(html(), "d.pdf"), DOC_EXTENSIONS, MAX));
    }

    /** 内容对但短于文件头长度，不能因越界读而误放行 */
    @Test
    void validate_truncatedHeader_rejected() {
        assertThrows(BusinessException.class,
                () -> validateAsImage(new byte[]{(byte) 0x89, 'P'}, "short.png"));
    }

    // ---------- 扩展名 / 大小基线 ----------

    @Test
    void validate_emptyFile_rejected() {
        assertThrows(BusinessException.class, () -> validateAsImage(new byte[0], "a.png"));
    }

    @Test
    void validate_oversize_rejected() {
        assertThrows(BusinessException.class, () -> FileValidator.validate(
                file(png(), "a.png"), FileValidator.IMAGE_EXTENSIONS, 8));
    }

    @Test
    void validate_extensionNotAllowed_rejected() {
        // 内容是真 PNG，但 exe 不在白名单，应在魔数校验前就被扩展名挡下
        assertThrows(BusinessException.class, () -> validateAsImage(png(), "a.exe"));
        assertThrows(BusinessException.class, () -> validateAsImage(png(), "noext"));
    }

    // ---------- MIME 服务端推导 ----------

    /** 无论客户端传什么 Content-Type，写入对象存储的 MIME 只由扩展名决定 */
    @Test
    void contentTypeOf_derivedFromExtension_notFromClient() {
        assertEquals("image/jpeg", FileValidator.contentTypeOf("evil.jpg"));
        assertEquals("image/png", FileValidator.contentTypeOf("a.png"));
        assertEquals("image/gif", FileValidator.contentTypeOf("a.gif"));
        assertEquals("application/pdf", FileValidator.contentTypeOf("a.pdf"));
    }

    /** 未知/无扩展名回落到 octet-stream —— 浏览器只下载不渲染，避免被当页面执行 */
    @Test
    void contentTypeOf_unknownExtension_fallsBackToOctetStream() {
        assertEquals("application/octet-stream", FileValidator.contentTypeOf("x.unknown"));
        assertEquals("application/octet-stream", FileValidator.contentTypeOf("noext"));
        assertEquals("application/octet-stream", FileValidator.contentTypeOf(null));
    }

    @Test
    void contentTypeOf_extensionCaseInsensitive() {
        assertEquals("image/png", FileValidator.contentTypeOf("A.PNG"));
    }

    // ---------- extensionOf ----------

    @Test
    void extensionOf_variants() {
        assertEquals("png", FileValidator.extensionOf("a.PNG"));
        assertEquals("gz", FileValidator.extensionOf("a.tar.gz"));
        assertEquals("", FileValidator.extensionOf("noext"));
        assertEquals("", FileValidator.extensionOf(null));
    }

    // ---------- 辅助 ----------

    private void validateAsImage(byte[] content, String filename) {
        FileValidator.validate(file(content, filename), FileValidator.IMAGE_EXTENSIONS, MAX);
    }

    /** 客户端 Content-Type 一律传 text/html，确保校验与推导都不依赖它 */
    private MultipartFile file(byte[] content, String filename) {
        return new MockMultipartFile("file", filename, "text/html", content);
    }

    /** 补齐到 16 字节，模拟真实文件长度足以覆盖文件头。取 int 便于直接写 0xFF 与字符字面量 */
    private static byte[] pad(int... head) {
        byte[] out = new byte[16];
        for (int i = 0; i < Math.min(head.length, out.length); i++) {
            out[i] = (byte) head[i];
        }
        return out;
    }

    private static byte[] png() {
        return pad((byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    }

    private static byte[] jpeg() {
        return pad((byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    private static byte[] gif() {
        return pad('G', 'I', 'F', '8', '9', 'a');
    }

    private static byte[] webp() {
        return pad('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
    }

    private static byte[] bmp() {
        return pad('B', 'M');
    }

    private static byte[] pdf() {
        return pad('%', 'P', 'D', 'F', '-', '1', '.', '7');
    }

    private static byte[] zip() {
        return pad('P', 'K', 0x03, 0x04);
    }

    private static byte[] ole2() {
        return pad((byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1);
    }

    private static byte[] html() {
        return "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);
    }
}
