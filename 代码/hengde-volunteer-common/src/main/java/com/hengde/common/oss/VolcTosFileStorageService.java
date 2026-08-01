package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.volcengine.tos.model.object.PutObjectInput;
import com.volcengine.tos.transport.TransportConfig;
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
import java.util.UUID;

/**
 * 火山引擎对象存储 TOS 实现。
 *
 * <p>与 {@link AliyunOssFileStorageService} 行为对齐：{@link OssProperties#isEnabled()} 为 false 时
 * 不调 SDK，只打日志并返回占位 URL；TOS 客户端按配置懒加载一次后复用；上传/删除失败抛
 * {@link BusinessException} 交全局处理器兜底。</p>
 *
 * <p>仅当 {@code hengde.oss.provider=volc} 时装配（恒德实例使用），与阿里云实现互斥。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "hengde.oss", name = "provider", havingValue = "volc")
public class VolcTosFileStorageService implements FileStorageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OssProperties properties;

    /** TOS 客户端，首次真实上传时懒加载 */
    private volatile TOSV2 tosClient;

    @Autowired
    public void setProperties(OssProperties properties) {
        this.properties = properties;
    }

    @Override
    public String upload(MultipartFile file, String dir) {
        FileValidator.validate(file, properties.getAllowedExtensions(), properties.getMaxFileSize());
        String objectName = buildObjectName(dir, file.getOriginalFilename());
        if (!properties.isEnabled()) {
            log.info("[TOS-MOCK] 未启用真实上传，objectName={} size={}", objectName, file.getSize());
            return "[oss-disabled]/" + objectName;
        }
        try (InputStream in = file.getInputStream()) {
            // Content-Type 由扩展名在服务端推导，刻意不采信 file.getContentType()——那来自客户端请求头、
            // 可伪造成 text/html，被对象存储原样回源后会在桶域名下形成存储型 XSS
            putObject(objectName, in, file.getSize(), FileValidator.contentTypeOf(file.getOriginalFilename()));
            return url(objectName);
        } catch (Exception e) {
            log.error("[TOS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public String upload(byte[] data, String objectName, String contentType) {
        if (!properties.isEnabled()) {
            log.info("[TOS-MOCK] 未启用真实上传，objectName={} size={}", objectName, data.length);
            return "[oss-disabled]/" + objectName;
        }
        try {
            putObject(objectName, new ByteArrayInputStream(data), data.length, contentType);
            return url(objectName);
        } catch (Exception e) {
            log.error("[TOS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public String uploadPrivate(byte[] data, String objectKey, String contentType) {
        if (!properties.isEnabled()) {
            log.info("[TOS-MOCK] 未启用真实上传（私有），objectKey={} size={}", objectKey, data.length);
            return objectKey;
        }
        try {
            // 走独立的私有分支：**绝不调用会打 ACL_PUBLIC_READ 的 putObject(...)**。
            // 私有对象无视 public-read 配置——证书不能因为一项全局开关就变成谁都能取。
            ObjectMetaRequestOptions options = new ObjectMetaRequestOptions().setContentLength(data.length);
            if (StringUtils.hasText(contentType)) {
                options.setContentType(contentType);
            }
            options.setAclType(ACLType.ACL_PRIVATE);
            client().putObject(new PutObjectInput()
                    .setBucket(properties.getBucket())
                    .setKey(objectKey)
                    .setContent(new ByteArrayInputStream(data))
                    .setOptions(options));
            return objectKey;
        } catch (Exception e) {
            log.error("[TOS] 私有上传失败 objectKey={}", objectKey, e);
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
        try (var out = client().getObject(new com.volcengine.tos.model.object.GetObjectV2Input()
                .setBucket(properties.getBucket()).setKey(objectKey));
             var in = out.getContent()) {
            long max = properties.getMaxDownloadBytes();
            // 先用服务端返回的 Content-Length 挡一道：超限直接拒，不把它读进堆
            // （与阿里云实现保持一致——两家在同一件事上的行为不该有差别）
            FileStorageService.requireWithinDownloadLimit(out.getContentLength(), max, objectKey);
            // Content-Length 不可信时（分块/未设）再兜一道：多读 1 字节，超了就说明谎报
            byte[] data = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
            FileStorageService.requireWithinDownloadLimit(data.length, max, objectKey);
            return data;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TOS] 读取对象失败 objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件读取失败：" + objectKey);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        FileStorageService.requireValidTtl(ttl, properties.getPresignMaxTtlSeconds());
        if (!properties.isEnabled()) {
            log.info("[TOS-MOCK] 未启用真实签名，objectKey={} ttl={}s", objectKey, ttl.toSeconds());
            return "[tos-disabled]/" + objectKey + "?expires=" + ttl.toSeconds();
        }
        try {
            PreSignedURLOutput out = client().preSignedURL(new PreSignedURLInput()
                    .setHttpMethod("GET")
                    .setBucket(properties.getBucket())
                    .setKey(objectKey)
                    .setExpires(ttl.toSeconds()));
            return out.getSignedUrl();
        } catch (Exception e) {
            log.error("[TOS] 生成签名 URL 失败 objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件下载失败，请稍后重试");
        }
    }

    @Override
    public void delete(String objectName) {
        if (!properties.isEnabled()) {
            log.info("[TOS-MOCK] 未启用真实删除，objectName={}", objectName);
            return;
        }
        try {
            client().deleteObject(new DeleteObjectInput().setBucket(properties.getBucket()).setKey(objectName));
        } catch (Exception e) {
            log.error("[TOS] 删除失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件删除失败");
        }
    }

    private void putObject(String objectName, InputStream content, long contentLength, String contentType) {
        ObjectMetaRequestOptions options = new ObjectMetaRequestOptions().setContentLength(contentLength);
        if (StringUtils.hasText(contentType)) {
            options.setContentType(contentType);
        }
        if (properties.isPublicRead()) {
            // 显式给对象打公共读，避免桶默认私有导致上传成功但 URL 访问 403
            options.setAclType(ACLType.ACL_PUBLIC_READ);
        }
        PutObjectInput input = new PutObjectInput()
                .setBucket(properties.getBucket())
                .setKey(objectName)
                .setContent(content)
                .setOptions(options);
        client().putObject(input);
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

    /** 懒加载 TOS 客户端：双重检查，保证只构建一次并复用 */
    private TOSV2 client() {
        if (tosClient == null) {
            synchronized (this) {
                if (tosClient == null) {
                    TransportConfig transport = TransportConfig.builder()
                            .connectTimeoutMills(properties.getConnectTimeoutMs())
                            .readTimeoutMills(properties.getReadTimeoutMs())
                            .writeTimeoutMills(properties.getWriteTimeoutMs())
                            .build();
                    tosClient = new TOSV2ClientBuilder().build(
                            properties.getRegion(),
                            properties.getEndpoint(),
                            properties.getAccessKeyId(),
                            properties.getAccessKeySecret(),
                            transport);
                }
            }
        }
        return tosClient;
    }
}
