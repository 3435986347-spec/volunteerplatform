package com.hengde.honor;

import com.hengde.common.oss.FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版对象存储，供证书用例替换真实 OSS/TOS。
 *
 * <p><b>为什么替换而不是连真实桶</b>：真实实现要凭证、要网络，测试跑不了；
 * 而「私有上传不打公共读 / 签名 TTL 有界」这些存储层保证已由 common 的
 * {@code PrivateObjectStorageTest} 用 mock SDK 覆盖。本模块要测的是<b>证书业务</b>——
 * 懒渲染的幂等、下载计数、归属校验，与底层是哪家存储无关。</p>
 *
 * <p>保留字节内容便于断言「第二次下载没有重新渲染」（对象未被覆盖）。</p>
 *
 * @author hengde
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryFileStorageConfig {

    /** 已写入的对象：key -> 字节 */
    public static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();

    /** 每个 key 被写入的次数——用于证明懒渲染没有重复渲染/覆盖 */
    public static final Map<String, Integer> WRITE_COUNT = new ConcurrentHashMap<>();

    /**
     * 私有上传<b>之前</b>执行的钩子，缺省什么也不做。
     *
     * <p><b>为什么要留这个缝</b>：有几个 bug 只在「A 读到了，B 抢先改了，A 再写」这个交错里现形，
     * 顺序调用永远测不出来（例如上传证书时并发软删——{@code createForSlot} 会把软删行复活，
     * 先删再传是测不到的）。用真线程去凑这个时序既慢又偶发；
     * 存储写入正好落在「读」与「写」中间，从这里注入即可<b>确定性地</b>造出那个交错。</p>
     */
    public static volatile Runnable BEFORE_UPLOAD_PRIVATE = () -> {
    };

    /** 签名<b>之前</b>执行的钩子（可抛异常），用于验证签名失败时下载计数会回退。 */
    public static volatile Runnable BEFORE_PRESIGN = () -> {
    };

    /** 每个用例用完必须复位，否则钩子会污染同上下文里的其他用例。 */
    public static void resetHooks() {
        BEFORE_UPLOAD_PRIVATE = () -> {
        };
        BEFORE_PRESIGN = () -> {
        };
    }

    @Bean
    @Primary
    public FileStorageService inMemoryFileStorageService() {
        return new FileStorageService() {
            @Override
            public String upload(MultipartFile file, String dir) {
                throw new UnsupportedOperationException("证书不走公共读上传");
            }

            @Override
            public String upload(byte[] data, String objectName, String contentType) {
                throw new UnsupportedOperationException("证书不走公共读上传");
            }

            @Override
            public void delete(String objectName) {
                OBJECTS.remove(objectName);
            }

            @Override
            public String uploadPrivate(byte[] data, String objectKey, String contentType) {
                BEFORE_UPLOAD_PRIVATE.run();
                OBJECTS.put(objectKey, data);
                WRITE_COUNT.merge(objectKey, 1, Integer::sum);
                return objectKey;
            }

            @Override
            public byte[] download(String objectKey) {
                byte[] data = OBJECTS.get(objectKey);
                if (data == null) {
                    // 与真实实现同样「读不到就明确失败」，不返回空数组——
                    // 空数组会被证书渲染当成一份坏底图，错误往后飘。
                    throw new com.hengde.common.exception.BusinessException("文件读取失败：" + objectKey);
                }
                return data;
            }

            @Override
            public String presignGet(String objectKey, Duration ttl) {
                BEFORE_PRESIGN.run();
                // 与真实实现同样对 TTL 做有界校验，避免用例在这里绕过了真实约束
                FileStorageService.requireValidTtl(ttl, 300);
                if (!OBJECTS.containsKey(objectKey)) {
                    throw new IllegalStateException("对象不存在：" + objectKey);
                }
                return "https://test-bucket.example/" + objectKey + "?sig=fake&ttl=" + ttl.toSeconds();
            }
        };
    }
}
