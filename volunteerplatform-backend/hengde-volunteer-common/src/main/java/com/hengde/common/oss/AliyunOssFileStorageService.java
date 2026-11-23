package com.hengde.common.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 阿里云 OSS 文件存储实现。
 *
 * <p>设计与 {@link com.hengde.common.sms.SmsServiceImpl} 一致：
 * {@link OssProperties#isEnabled()} 为 false 时不调 SDK，只打日志并返回占位 URL；
 * OSS 客户端按配置懒加载一次后复用；上传失败抛 {@link BusinessException} 交全局处理器兜底。</p>
 *
 * <p>依赖按项目约定用 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "hengde.oss", name = "provider", havingValue = "aliyun", matchIfMissing = true)
public class AliyunOssFileStorageService implements FileStorageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OssProperties properties;

    /** OSS 客户端，首次真实上传时懒加载 */
    private volatile OSS ossClient;

    @Autowired
    public void setProperties(OssProperties properties) {
        this.properties = properties;
    }

    @Override
    public String upload(MultipartFile file, String dir) {
        FileValidator.validate(file, properties.getAllowedExtensions(), properties.getMaxFileSize());
        String objectName = buildObjectName(dir, file.getOriginalFilename());
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实上传，objectName={} size={}", objectName, file.getSize());
            return "[oss-disabled]/" + objectName;
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(file.getSize());
        // Content-Type 由扩展名在服务端推导，刻意不采信 file.getContentType()——那来自客户端请求头、
        // 可伪造成 text/html，被对象存储原样回源后会在桶域名下形成存储型 XSS
        meta.setContentType(FileValidator.contentTypeOf(file.getOriginalFilename()));
        applyAcl(meta);
        try (InputStream in = file.getInputStream()) {
            client().putObject(properties.getBucket(), objectName, in, meta);
            return url(objectName);
        } catch (Exception e) {
            log.error("[OSS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public String upload(byte[] data, String objectName, String contentType) {
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实上传，objectName={} size={}", objectName, data.length);
            return "[oss-disabled]/" + objectName;
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (StringUtils.hasText(contentType)) {
            meta.setContentType(contentType);
        }
        applyAcl(meta);
        try {
            client().putObject(properties.getBucket(), objectName, new ByteArrayInputStream(data), meta);
            return url(objectName);
        } catch (Exception e) {
            log.error("[OSS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public String uploadPrivate(byte[] data, String objectKey, String contentType) {
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实上传（私有），objectKey={} size={}", objectKey, data.length);
            return objectKey;
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (StringUtils.hasText(contentType)) {
            meta.setContentType(contentType);
        }
        // 刻意不调 applyAcl：私有对象**无视** public-read 配置，绝不打公共读。
        // 对象级 ACL **优先于桶级 ACL**（阿里 OSS / 火山 TOS 官方规则一致），
        // 故即便桶是公共读，Private 对象仍然不可匿名读——但**桶 Policy、CDN 回源、
        // 其他授权方式仍可能绕过对象 ACL**，最终必须以真实匿名访问验证为准。
        meta.setObjectAcl(CannedAccessControlList.Private);
        try {
            client().putObject(properties.getBucket(), objectKey, new ByteArrayInputStream(data), meta);
            return objectKey;
        } catch (Exception e) {
            log.error("[OSS] 私有上传失败 objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public byte[] download(String objectKey) {
        if (!properties.isEnabled()) {
            // 与上传/签名的 mock 分支一致：未启用真实存储时不假装成功，
            // 让调用方（证书渲染）拿到明确失败，而不是收到一个空文件当成底图。
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(),
                    "对象存储未启用，无法读取文件：" + objectKey);
        }
        try (var obj = client().getObject(properties.getBucket(), objectKey);
             var in = obj.getObjectContent()) {
            long max = properties.getMaxDownloadBytes();
            // 先用服务端返回的 Content-Length 挡一道：超限直接拒，不把它读进堆
            FileStorageService.requireWithinDownloadLimit(
                    obj.getObjectMetadata().getContentLength(), max, objectKey);
            // Content-Length 不可信时（分块/未设）再兜一道：多读 1 字节，超了就说明谎报
            byte[] data = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
            FileStorageService.requireWithinDownloadLimit(data.length, max, objectKey);
            return data;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[OSS] 读取对象失败 objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件读取失败：" + objectKey);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        FileStorageService.requireValidTtl(ttl, properties.getPresignMaxTtlSeconds());
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实签名，objectKey={} ttl={}s", objectKey, ttl.toSeconds());
            return "[oss-disabled]/" + objectKey + "?expires=" + ttl.toSeconds();
        }
        try {
            Date expiry = new Date(System.currentTimeMillis() + ttl.toMillis());
            return client().generatePresignedUrl(properties.getBucket(), objectKey, expiry).toString();
        } catch (Exception e) {
            log.error("[OSS] 生成签名 URL 失败 objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件下载失败，请稍后重试");
        }
    }

    @Override
    public void delete(String objectName) {
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实删除，objectName={}", objectName);
            return;
        }
        try {
            client().deleteObject(properties.getBucket(), objectName);
        } catch (Exception e) {
            log.error("[OSS] 删除失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件删除失败");
        }
    }

    /** 生成对象名：{@code dir/yyyyMMdd/UUID.ext}，避免重名覆盖 */
    private String buildObjectName(String dir, String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        String prefix = StringUtils.hasText(dir) ? trimSlash(dir) + "/" : "";
        return prefix + LocalDate.now().format(DATE_FMT) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
    }

    /** 阿里云 V1 签名不含 Content-Length，声明的大小在这一侧约束不住，只能靠前端（火山那一侧会进签名）。 */
    @Override
    public PresignedUpload presignPut(String dir, String extension, long contentLength, Duration ttl) {
        FileStorageService.requireValidTtl(ttl, properties.getPresignMaxTtlSeconds());
        String objectName = buildObjectName(dir, "upload." + extension);
        String contentType = FileValidator.contentTypeOf(objectName);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        if (properties.isPublicRead()) {
            headers.put("x-oss-object-acl", "public-read");
        }
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实直传签名，objectName={} size={}", objectName, contentLength);
            return new PresignedUpload("[oss-disabled]/" + objectName + "?expires=" + ttl.toSeconds(), "PUT", headers,
                    "[oss-disabled]/" + objectName, objectName);
        }
        try {
            GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), objectName, HttpMethod.PUT);
            req.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
            req.setContentType(contentType);
            if (properties.isPublicRead()) {
                req.addHeader("x-oss-object-acl", "public-read");
            }
            return new PresignedUpload(client().generatePresignedUrl(req).toString(), "PUT", headers, url(objectName), objectName);
        } catch (Exception e) {
            log.error("[OSS] 生成直传签名失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "上传准备失败，请稍后重试");
        }
    }

    @Override
    public boolean isOwnUpload(String url, String dir) {
        // 未启用真实存储时 upload 返回的是占位 URL，前缀要与之对得上，本地联调才走得通
        String base = properties.isEnabled() ? url("").replaceAll("/$", "") : "[oss-disabled]";
        return FileStorageService.matchesUploadedName(url, base, dir);
    }

    /** 拼接可公开访问 URL：优先用配置的 urlPrefix，否则按「桶名 + endpoint」拼默认外网域名 */
    private String url(String objectName) {
        if (StringUtils.hasText(properties.getUrlPrefix())) {
            return trimSlash(properties.getUrlPrefix()) + "/" + objectName;
        }
        String host = properties.getEndpoint().replaceFirst("^https?://", "");
        return "https://" + properties.getBucket() + "." + host + "/" + objectName;
    }

    private String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 按配置给对象打「公共读」ACL；避免桶默认私有导致上传成功但 URL 访问 403 */
    private void applyAcl(ObjectMetadata meta) {
        if (properties.isPublicRead()) {
            meta.setObjectAcl(CannedAccessControlList.PublicRead);
        }
    }

    /** 懒加载 OSS 客户端：双重检查，保证只构建一次并复用 */
    private OSS client() {
        if (ossClient == null) {
            synchronized (this) {
                if (ossClient == null) {
                    ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
                    conf.setConnectionTimeout(properties.getConnectTimeoutMs());
                    conf.setSocketTimeout(properties.getReadTimeoutMs());
                    ossClient = new OSSClientBuilder().build(
                            properties.getEndpoint(),
                            properties.getAccessKeyId(),
                            properties.getAccessKeySecret(),
                            conf);
                }
            }
        }
        return ossClient;
    }
}
