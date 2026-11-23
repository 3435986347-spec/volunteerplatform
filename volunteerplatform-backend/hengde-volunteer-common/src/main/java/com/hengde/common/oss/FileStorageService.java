package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 文件存储服务，封装对象存储的上传/删除。
 *
 * <p>这是 common 提供的「存储层」能力：业务（头像、活动照片、公示图片、文件下载等）
 * 调本接口上传，拿到可访问 URL 存库即可，不关心底层是哪家对象存储。
 * 当前实现为阿里云 OSS（{@link AliyunOssFileStorageService}），后续若换 MinIO 只需另写实现。</p>
 *
 * @author hengde
 */
public interface FileStorageService {

    /**
     * 上传一个上传文件（来自 Controller 的 MultipartFile）。
     *
     * <p>对象名按 {@code dir/yyyyMMdd/UUID.ext} 自动生成，避免重名覆盖。</p>
     *
     * @param file 上传文件
     * @param dir  业务目录（如 avatar、activity、banner）
     * @return 可公开访问的文件 URL
     */
    String upload(MultipartFile file, String dir);

    /**
     * 这个 URL 是不是本系统经 {@link #upload(MultipartFile, String)} 传到 {@code dir} 目录下的对象。
     *
     * <p>给「客户端把上传得到的 URL 回传回来」的场景校验用（问卷的文件题、申诉凭证之类）：不校验的话，
     * 答卷里可以塞任意外链，管理员在后台点开的就是别人的页面。判据是<b>前缀 + 本实现生成的对象名形状</b>
     * （{@code dir/yyyyMMdd/32位十六进制[.扩展名]}），所以 {@code ../}、查询串、别的目录一概不认。</p>
     *
     * <p>默认不支持：测试里的替身实现不必各写一份，真实的两家实现都覆盖了它。</p>
     */
    default boolean isOwnUpload(String url, String dir) {
        throw new UnsupportedOperationException("当前存储实现不支持校验上传 URL");
    }

    /**
     * {@link #isOwnUpload} 的共用判定：{@code url} 以 {@code base + "/" + dir + "/"} 开头，余下部分是本项目生成的对象名。
     * 两家实现共用，保证口径一致。
     */
    static boolean matchesUploadedName(String url, String base, String dir) {
        if (url == null || base == null || dir == null || dir.isBlank()) {
            return false;
        }
        String prefix = base + "/" + dir + "/";
        return url.startsWith(prefix)
                && url.substring(prefix.length()).matches("\\d{8}/[0-9a-f]{32}(\\.[A-Za-z0-9]{1,10})?");
    }

    /**
     * 上传字节数据（用于生成类文件，如二维码、PDF 证书）。
     *
     * @param data        文件字节
     * @param objectName  对象名（含路径，如 cert/2026/abc.pdf）
     * @param contentType MIME 类型，可为 null
     * @return 可公开访问的文件 URL
     */
    String upload(byte[] data, String objectName, String contentType);

    /**
     * 删除对象。
     *
     * @param objectName 对象名（不含域名的存储路径）
     */
    void delete(String objectName);

    // ---------- 私有对象：证书等不得公开可取的文件 ----------

    /**
     * 上传<b>私有</b>对象，返回 {@code objectKey}（<b>不是 URL</b>）。
     *
     * <p><b>为什么必须单开一个方法，而不是「库里只存 key」就够了</b>：上面两个 {@code upload} 会按
     * {@code hengde.oss.public-read} 给对象打<b>公共读 ACL</b>（TOS 的 {@code ACL_PUBLIC_READ} /
     * OSS 的 {@code PublicRead}）。只要 ACL 是公共读，对象就能被任何人凭 URL 直接取走——
     * <b>数据库里存 key 还是存 URL，对桶上的可访问性毫无影响</b>，key 也不是秘密（可枚举、会出现在日志里）。
     * 证书这类文件必须在<b>上传时</b>就是私有的。</p>
     *
     * <p>本方法<b>永不设置公共读 ACL</b>，无视 {@code public-read} 配置。取用一律走
     * {@link #presignGet(String, Duration)}。</p>
     *
     * @param data        文件字节
     * @param objectKey   对象 key（含路径，如 {@code cert/2026/abc.pdf}）
     * @param contentType MIME 类型，可为 null
     * @return 对象 key（原样返回，便于调用方链式存库）
     */
    String uploadPrivate(byte[] data, String objectKey, String contentType);

    /**
     * 把对象<b>读回字节</b>（服务端内部使用）。
     *
     * <p>用途是服务端自己要处理这个文件——例如证书渲染需要把协会的「电子样本」当底图套印，
     * 那份底图上就带着公章，这正是 Row 36「自动生成一个<b>盖章的</b>电子证书」的落点。</p>
     *
     * <p><b>不能用 {@link #presignGet} 代替</b>：那是给浏览器的临时链接，
     * 服务端拿它还得再发一次 HTTP 绕回对象存储，且把一次内部读取变成了对签名 URL 可达性的依赖。</p>
     *
     * <p><b>也不要用它做用户下载的中转</b>——那会让所有证书流量穿过应用服务器，
     * 白吃带宽和堆内存；用户下载一律走 {@link #presignGet}。</p>
     *
     * @param objectKey 对象 key
     * @return 对象字节
     * @throws BusinessException 对象不存在或读取失败
     */
    byte[] download(String objectKey);

    /**
     * 读回对象时的大小上限守卫。两家实现共用，保证口径一致。
     *
     * <p><b>为什么必须有</b>：{@code readAllBytes()} 会把整个对象读进堆。桶里若有一份几百 MB 的文件
     * （误传、或被人替换），一次读取就能把应用打到 OOM——而这是<b>服务端主动发起</b>的读取，
     * 没有任何前端限流挡得住。上限从 {@code hengde.oss.max-download-bytes} 来。</p>
     *
     * @param actualBytes 已知或已读出的字节数
     * @param maxBytes    上限
     * @param objectKey   仅用于报错信息
     */
    static void requireWithinDownloadLimit(long actualBytes, long maxBytes, String objectKey) {
        // fail-closed：上限配错时拒绝读取，而不是当成「不限」
        if (maxBytes <= 0) {
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(),
                    "对象读取上限配置非法（hengde.oss.max-download-bytes = " + maxBytes + "），须 > 0");
        }
        if (actualBytes > maxBytes) {
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(),
                    "对象过大，拒绝读取：" + objectKey + "（" + actualBytes + " > " + maxBytes + " 字节）");
        }
    }

    /**
     * 为私有对象生成<b>短期有效</b>的签名下载 URL。
     *
     * <p>调用方（如证书下载接口）应先校验归属与权限，再换取签名 URL 并 302 或直接返回。
     * <b>签名 URL 不要落库、不要写进日志</b>——它在有效期内等同于凭证。</p>
     *
     * @param objectKey 对象 key
     * @param ttl       有效期；须为正且不超过 {@code hengde.oss.presign-max-ttl-seconds}（默认 5 分钟）
     * @return 带签名的临时 URL
     * @throws com.hengde.common.exception.BusinessException TTL 非正或超过上限
     */
    String presignGet(String objectKey, Duration ttl);

    /**
     * 签一条限时的直传 PUT（V4 社区核心批：帖子视频不经服务端中转，Q11 默认 100MB）。
     *
     * <p>对象键与 {@link #upload(MultipartFile, String)} 同形（{@code dir/yyyyMMdd/uuid.ext}），所以传完之后
     * {@link #isOwnUpload} 认得出它。Content-Type 由扩展名在服务端推导；声明的大小进签名（存储侧支持时），传的不是这个大小会被拒收。</p>
     *
     * <p>⚠️ 真实对象存储上的直传<b>没有端到端验证过</b>；存储未启用时返回占位地址（与 {@code upload} 的占位 URL 同前缀），供本地联调。</p>
     *
     * @param dir           目录
     * @param extension     扩展名（不带点，已由调用方按白名单校验）
     * @param contentLength 声明的字节数
     * @param ttl           有效期（受 {@code hengde.oss.presign-max-ttl-seconds} 约束）
     */
    default PresignedUpload presignPut(String dir, String extension, long contentLength, Duration ttl) {
        throw new UnsupportedOperationException("当前存储实现不支持直传签名");
    }

    /**
     * 校验签名有效期。两家实现共用，保证口径一致——各写一份迟早漂开。
     *
     * <p><b>超限直接拒绝，不截断</b>：截断会让调用方以为拿到了自己要求的时长，
     * 问题被推迟到线上才暴露；拒绝则在开发期就暴露。</p>
     *
     * @param ttl        请求的有效期
     * @param maxSeconds 上限（秒）
     * @throws BusinessException TTL 为 null / 非正 / 超过上限
     */
    static void requireValidTtl(Duration ttl, int maxSeconds) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "签名有效期必须为正");
        }
        // **fail-closed**：上限本身非法时拒绝签发，而不是当成「不限」。
        // 早先写的是 `maxSeconds > 0 && ...`，等于把「配置写错」翻译成「关闭安全上限」——
        // 一个配置失误就让证书链接可以签成任意时长，且没有任何报错。
        if (maxSeconds <= 0) {
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(),
                    "签名有效期上限配置非法（hengde.oss.presign-max-ttl-seconds = " + maxSeconds + "），须 > 0");
        }
        // 用 Duration 直接比，**不要先 toSeconds()**：那是向下取整，
        // 300.5s 会被算成 300s 而放行，阿里云那边按毫秒签出 300.5s——上限被亚秒精度绕过。
        if (ttl.compareTo(Duration.ofSeconds(maxSeconds)) > 0) {
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(),
                    "签名有效期超过上限：请求 " + ttl.toMillis() + "ms，上限 " + maxSeconds + "s");
        }
    }
}
