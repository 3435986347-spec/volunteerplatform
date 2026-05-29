package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PutObjectInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
            putObject(objectName, in, file.getSize(), file.getContentType());
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
                    tosClient = new TOSV2ClientBuilder().build(
                            properties.getRegion(),
                            properties.getEndpoint(),
                            properties.getAccessKeyId(),
                            properties.getAccessKeySecret());
                }
            }
        }
        return tosClient;
    }
}
