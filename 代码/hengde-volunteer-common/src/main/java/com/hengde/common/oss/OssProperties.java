package com.hengde.common.oss;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 对象存储配置，绑定 application.yaml 里的 {@code hengde.oss.*}。
 *
 * <p>白标多实例部署下，每个社会组织一套独立的对象存储凭证与存储桶，因此都来自配置。
 * {@link #enabled} 为 false 时（dev/test 默认）{@link FileStorageService} 不真实上传，
 * 只打日志并返回占位 URL，便于无凭证下跑通本地与测试。</p>
 *
 * <p>{@link #provider} 决定启用哪家实现：{@code aliyun}（默认，{@link AliyunOssFileStorageService}）
 * 或 {@code volc}（火山引擎 TOS，{@code VolcTosFileStorageService}）；两者实现同一接口，业务无感。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.oss")
public class OssProperties {

    /** 存储厂商：aliyun（默认）/ volc（火山引擎 TOS）。决定激活哪个 FileStorageService 实现 */
    private String provider = "aliyun";

    /** 是否真实上传。false 时仅打日志返回占位 URL（dev/test 默认） */
    private boolean enabled = false;

    /** 服务接入点：阿里云形如 https://oss-cn-shenzhen.aliyuncs.com；火山 TOS 形如 tos-cn-beijing.volces.com */
    private String endpoint;

    /** 地域，火山引擎 TOS 客户端必填（如 cn-beijing）；阿里云 OSS 用 endpoint 即可，可留空 */
    private String region;

    /** 存储桶名 */
    private String bucket;

    /** 访问凭证 AccessKeyId */
    private String accessKeyId;

    /** 访问凭证 AccessKeySecret */
    private String accessKeySecret;

    /**
     * 访问 URL 前缀（如绑定了 CDN/自定义域名时填写，形如 https://cdn.example.com）。
     * 留空则按「桶名 + endpoint」拼默认外网访问域名。
     */
    private String urlPrefix;

    /**
     * 是否把上传对象显式设为「公共读」ACL。
     *
     * <p>本项目的上传物（头像、i 志愿者码、轮播图、活动封面、公告图等）URL 直接存库，
     * 由小程序 {@code <image>} 与后台 {@code <img>} 匿名访问，因此默认开启；
     * 桶本身已是公共读时重复设置也无害。若桶启用了「阻止公共访问」类开关，
     * 对象级 ACL 会被桶策略覆盖，仍需在控制台放开。</p>
     */
    private boolean publicRead = true;

    /**
     * 私有对象签名 URL 的<b>最大</b>有效期（秒），默认 300（5 分钟）。
     *
     * <p>{@code presignGet} 强制这个上限：<b>超过即拒绝</b>，而不是悄悄截断到上限——
     * 截断会让调用方以为拿到了自己要的时长，把问题推迟到线上才暴露。</p>
     *
     * <p><b>为什么只校验「TTL 为正」不够</b>：签名 URL 在有效期内<b>等同于凭证</b>，谁拿到谁就能取文件。
     * 证书上有真实姓名与身份信息，一条 7 天有效的链接被转发、进日志或被爬，就是长期泄露。
     * 只拒绝非正值的话，{@code Duration.ofDays(3650)} 照样放行，与隐私设计写的
     * 「短期签名 URL（建议 ≤5 分钟）」直接矛盾。</p>
     */
    private int presignMaxTtlSeconds = 300;

    /** 建立连接超时（毫秒） */
    private int connectTimeoutMs = 30_000;

    /**
     * 读取响应超时（毫秒）。
     *
     * <p>网络较差时上传大文件最容易卡在这一项——请求体已发出但迟迟等不到响应，
     * 表现为 {@code SocketTimeoutException: Read timed out}。默认给得比较宽松。</p>
     */
    private int readTimeoutMs = 120_000;

    /** 写入请求体超时（毫秒），上传大文件时生效 */
    private int writeTimeoutMs = 120_000;

    /** 单文件大小上限（字节），默认 10MB */
    private long maxFileSize = 10 * 1024 * 1024L;

    /**
     * 服务端<b>回读</b>对象的大小上限（字节），默认 32MB。
     *
     * <p>与 {@link #maxFileSize} 分开：那条管的是「用户能传多大」，这条管的是
     * 「服务端一次能把多大的东西读进堆」。{@code download()} 走 {@code readAllBytes()}，
     * 桶里若有一份几百 MB 的文件（误传或被替换），一次读取就能把应用打到 OOM，
     * 而这是服务端主动发起的读取，前端限流挡不住。</p>
     */
    private long maxDownloadBytes = 32 * 1024 * 1024L;

    /** 允许上传的扩展名（小写，不含点），作为 upload(MultipartFile) 的基线校验 */
    private Set<String> allowedExtensions = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx");

    /**
     * 启动即校验安全相关配置，**配错就不让启动**。
     *
     * <p>{@link #presignMaxTtlSeconds} 若被配成 0 或负数，签名上限就形同虚设。
     * 与其在运行期第一次下载证书时才报错（那时错误只会进日志、没人看），
     * 不如在启动阶段直接失败——配置错误应当在部署时暴露，而不是等到线上签出一条十年有效的链接。</p>
     */
    @PostConstruct
    void validate() {
        if (presignMaxTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "hengde.oss.presign-max-ttl-seconds 必须 > 0，当前为 " + presignMaxTtlSeconds
                            + "。该项是私有文件签名 URL 的有效期上限，配成 0/负数等于关闭这道安全限制。");
        }
    }
}
