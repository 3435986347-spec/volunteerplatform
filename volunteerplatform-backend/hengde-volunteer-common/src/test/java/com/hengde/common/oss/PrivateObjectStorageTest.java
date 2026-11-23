package com.hengde.common.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import com.hengde.common.exception.BusinessException;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.volcengine.tos.model.object.PutObjectInput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 私有对象存储的<b>回归网</b>：证书类文件不得被打成公共读。
 *
 * <p><b>为什么必须有这组用例</b>：`hengde.oss.public-read` 默认就是 {@code true}，两个既有
 * {@code upload} 会据此给对象打公共读 ACL（OSS 的 {@code PublicRead} / TOS 的 {@code ACL_PUBLIC_READ}）。
 * 「数据库只存 objectKey 不存 URL」<b>完全不影响桶上的可访问性</b>——ACL 是公共读，key 又可枚举、
 * 还会出现在日志里，证书等于对全网开放。故新增了 {@code uploadPrivate}/{@code presignGet}，
 * 本类把「私有路径绝不打公共读」钉死。</p>
 *
 * <p><b>每个断言都配一条反向断言</b>：只断言私有路径是 Private 是不够的——如果哪天 ACL 根本没被设置，
 * 断言可能因为默认值而恰好通过。故同时断言<b>普通路径在同样配置下确实是 PublicRead</b>，
 * 两条一起才证明「两条路径真的不同」，而不是「ACL 压根没生效」。</p>
 *
 * <p><b>本类证明什么、不证明什么</b>（别高估）：</p>
 * <ul>
 *   <li><b>证明</b>：我方代码<b>请求</b>的是私有 ACL、签名 URL 带有界 TTL、私有上传返回的是 key 而非 URL；</li>
 *   <li><b>不证明</b>：匿名请求真的取不到对象、签名过期后真的失效——那取决于真实服务端行为，
 *       必须在真实桶上验证，属部署检查项。<b>注意对象级 ACL 优先于桶级 ACL</b>
 *       （两家官方规则一致），故 Private 对象即便放在公共读的桶里也不可匿名读；
 *       但<b>桶 Policy、CDN 回源或其他授权方式仍可能绕过对象 ACL</b>，这才是要实测的原因。</li>
 * </ul>
 *
 * @author hengde
 */
class PrivateObjectStorageTest {

    private static final byte[] PDF = "%PDF-1.7 fake".getBytes();
    private static final String KEY = "cert/2026/abc.pdf";

    /** 最坏配置：真实启用 + 公共读开着。私有路径必须在这种配置下依然是私有的。 */
    private OssProperties worstCaseProperties() {
        OssProperties p = new OssProperties();
        p.setEnabled(true);
        p.setPublicRead(true);
        p.setBucket("test-bucket");
        p.setEndpoint("https://oss-cn-shenzhen.aliyuncs.com");
        return p;
    }

    /**
     * 读回对象 ACL。{@code ObjectMetadata} 只有 {@code setObjectAcl} 没有 getter——
     * 它把值写进 raw metadata 的 {@code x-oss-object-acl} 头，故从那里取。
     */
    private static String aclOf(ObjectMetadata meta) {
        Object v = meta.getRawMetadata().get("x-oss-object-acl");
        return v == null ? null : v.toString();
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ================= 阿里云 OSS =================

    @Test
    void aliyun_uploadPrivate_neverSetsPublicRead() throws Exception {
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(worstCaseProperties());
        OSS client = mock(OSS.class);
        inject(svc, "ossClient", client);

        String returned = svc.uploadPrivate(PDF, KEY, "application/pdf");

        ArgumentCaptor<ObjectMetadata> meta = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(client).putObject(anyString(), anyString(), any(InputStream.class), meta.capture());
        assertEquals(CannedAccessControlList.Private.toString(), aclOf(meta.getValue()),
                "私有上传必须显式打 Private，且不得受 public-read 配置影响");
        assertEquals(KEY, returned, "私有上传应返回 objectKey");
        assertFalse(returned.startsWith("http"), "私有上传绝不能返回可直接访问的 URL");
    }

    @Test
    void aliyun_plainUpload_stillPublicRead_soThePrivatePathIsGenuinelyDifferent() throws Exception {
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(worstCaseProperties());
        OSS client = mock(OSS.class);
        inject(svc, "ossClient", client);

        svc.upload(PDF, KEY, "application/pdf");

        ArgumentCaptor<ObjectMetadata> meta = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(client).putObject(anyString(), anyString(), any(InputStream.class), meta.capture());
        assertEquals(CannedAccessControlList.PublicRead.toString(), aclOf(meta.getValue()),
                "普通路径在 public-read=true 时应为公共读——否则上面那条私有断言可能只是「ACL 没生效」");
        assertNotEquals(CannedAccessControlList.Private.toString(), aclOf(meta.getValue()));
    }

    @Test
    void aliyun_presignGet_passesRequestedTtlThrough() throws Exception {
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(worstCaseProperties());
        OSS client = mock(OSS.class);
        when(client.generatePresignedUrl(anyString(), anyString(), any()))
                .thenReturn(new URL("https://example.com/signed?sig=x"));
        inject(svc, "ossClient", client);

        long before = System.currentTimeMillis();
        String url = svc.presignGet(KEY, Duration.ofMinutes(5));

        ArgumentCaptor<java.util.Date> expiry = ArgumentCaptor.forClass(java.util.Date.class);
        verify(client).generatePresignedUrl(anyString(), anyString(), expiry.capture());
        long delta = expiry.getValue().getTime() - before;
        assertTrue(delta > 0 && delta <= Duration.ofMinutes(5).toMillis() + 5_000,
                "签名有效期应约等于传入 TTL，实际偏移 " + delta + "ms");
        assertTrue(url.startsWith("https://"), "应返回签名 URL");
    }

    @Test
    void aliyun_presignGet_rejectsNonPositiveTtl() {
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(worstCaseProperties());
        assertThrows(BusinessException.class, () -> svc.presignGet(KEY, Duration.ZERO),
                "TTL 为 0 等于签发一个立即失效或永久有效的链接，须直接拒绝");
        assertThrows(BusinessException.class, () -> svc.presignGet(KEY, Duration.ofSeconds(-1)));
        assertThrows(BusinessException.class, () -> svc.presignGet(KEY, null));
    }

    // ================= 火山 TOS =================

    @Test
    void tos_uploadPrivate_neverSetsPublicRead() throws Exception {
        VolcTosFileStorageService svc = new VolcTosFileStorageService();
        svc.setProperties(worstCaseProperties());
        TOSV2 client = mock(TOSV2.class);
        inject(svc, "tosClient", client);

        String returned = svc.uploadPrivate(PDF, KEY, "application/pdf");

        ArgumentCaptor<PutObjectInput> input = ArgumentCaptor.forClass(PutObjectInput.class);
        verify(client).putObject(input.capture());
        assertEquals(ACLType.ACL_PRIVATE, input.getValue().getOptions().getAclType(),
                "私有上传必须显式打 ACL_PRIVATE，且不得受 public-read 配置影响");
        assertEquals(KEY, returned);
        assertFalse(returned.startsWith("http"), "私有上传绝不能返回可直接访问的 URL");
    }

    @Test
    void tos_plainUpload_stillPublicRead_soThePrivatePathIsGenuinelyDifferent() throws Exception {
        VolcTosFileStorageService svc = new VolcTosFileStorageService();
        svc.setProperties(worstCaseProperties());
        TOSV2 client = mock(TOSV2.class);
        inject(svc, "tosClient", client);

        svc.upload(PDF, KEY, "application/pdf");

        ArgumentCaptor<PutObjectInput> input = ArgumentCaptor.forClass(PutObjectInput.class);
        verify(client).putObject(input.capture());
        assertEquals(ACLType.ACL_PUBLIC_READ, input.getValue().getOptions().getAclType(),
                "普通路径在 public-read=true 时应为公共读——否则上面那条私有断言可能只是「ACL 没生效」");
    }

    @Test
    void tos_presignGet_passesTtlThrough() throws Exception {
        VolcTosFileStorageService svc = new VolcTosFileStorageService();
        svc.setProperties(worstCaseProperties());
        TOSV2 client = mock(TOSV2.class);
        when(client.preSignedURL(any(PreSignedURLInput.class)))
                .thenReturn(new PreSignedURLOutput("https://example.com/signed?sig=x", Map.of()));
        inject(svc, "tosClient", client);

        String url = svc.presignGet(KEY, Duration.ofMinutes(5));

        ArgumentCaptor<PreSignedURLInput> input = ArgumentCaptor.forClass(PreSignedURLInput.class);
        verify(client).preSignedURL(input.capture());
        assertEquals(300L, input.getValue().getExpires(), "TTL 应按秒传给 SDK");
        assertEquals("GET", input.getValue().getHttpMethod());
        assertEquals(KEY, input.getValue().getKey());
        assertTrue(url.startsWith("https://"));
    }

    @Test
    void tos_presignGet_rejectsNonPositiveTtl() {
        VolcTosFileStorageService svc = new VolcTosFileStorageService();
        svc.setProperties(worstCaseProperties());
        assertThrows(BusinessException.class, () -> svc.presignGet(KEY, Duration.ZERO));
        assertThrows(BusinessException.class, () -> svc.presignGet(KEY, null));
    }

    // ================= 签名有效期上限 =================

    /**
     * 超过上限必须<b>拒绝</b>，而不是截断到上限。
     *
     * <p>只拒绝非正值是不够的——那样 {@code Duration.ofDays(3650)} 也能通过，
     * 与隐私设计写的「短期签名 URL（建议 ≤5 分钟）」直接矛盾。签名 URL 在有效期内等同凭证。</p>
     *
     * <p>截断而不拒绝同样不行：调用方会以为拿到了自己要求的时长，问题推迟到线上才暴露。</p>
     */
    @Test
    void presignGet_rejectsTtlBeyondConfiguredMax() {
        OssProperties p = worstCaseProperties();
        assertEquals(300, p.getPresignMaxTtlSeconds(), "默认上限应为 5 分钟");

        AliyunOssFileStorageService oss = new AliyunOssFileStorageService();
        oss.setProperties(p);
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> oss.presignGet(KEY, Duration.ofDays(3650)),
                "十年有效的签名链接必须被拒绝");
        assertTrue(e1.getMessage().contains("上限"), "错误信息应点明是超上限：" + e1.getMessage());
        assertThrows(BusinessException.class, () -> oss.presignGet(KEY, Duration.ofSeconds(301)),
                "刚过上限一秒也要拒绝，不能四舍五入放行");

        VolcTosFileStorageService tos = new VolcTosFileStorageService();
        tos.setProperties(p);
        assertThrows(BusinessException.class, () -> tos.presignGet(KEY, Duration.ofHours(1)),
                "两家实现必须同口径——共用 requireValidTtl 就是为了这个");
    }

    @Test
    void presignGet_acceptsTtlExactlyAtMax() throws Exception {
        OssProperties p = worstCaseProperties();
        VolcTosFileStorageService tos = new VolcTosFileStorageService();
        tos.setProperties(p);
        TOSV2 client = mock(TOSV2.class);
        when(client.preSignedURL(any(PreSignedURLInput.class)))
                .thenReturn(new PreSignedURLOutput("https://example.com/signed", Map.of()));
        inject(tos, "tosClient", client);

        // 边界是「≤ 上限」而非「< 上限」——否则默认配置下连 5 分钟都用不了
        assertDoesNotThrow(() -> tos.presignGet(KEY, Duration.ofSeconds(300)));
    }

    /**
     * <b>亚秒不得绕过上限</b>：{@code Duration.ofMillis(300_500)} 折合 300.5 秒。
     *
     * <p>早先用 {@code ttl.toSeconds() > maxSeconds} 判断，而 {@code toSeconds()} <b>向下取整</b>，
     * 300.5s 被算成 300s 直接放行，阿里云那边却按毫秒签出 300.5s——上限被亚秒精度绕过。
     * 改用 {@code Duration.compareTo} 后才是真的「不超过」。</p>
     */
    @Test
    void presignGet_rejectsSubSecondOverflowBeyondMax() {
        OssProperties p = worstCaseProperties();   // 上限 300s
        AliyunOssFileStorageService oss = new AliyunOssFileStorageService();
        oss.setProperties(p);
        assertThrows(BusinessException.class, () -> oss.presignGet(KEY, Duration.ofMillis(300_500)),
                "300.5 秒超过 300 秒上限，不得因为向下取整而放行");

        VolcTosFileStorageService tos = new VolcTosFileStorageService();
        tos.setProperties(p);
        assertThrows(BusinessException.class, () -> tos.presignGet(KEY, Duration.ofMillis(300_001)),
                "多 1 毫秒也算超限——两家同口径");
    }

    /**
     * 上限配成 0 / 负数是<b>配置错误</b>，必须 fail-closed（拒绝签发），不得当成「不限」。
     *
     * <p>早先写的是 {@code maxSeconds > 0 && ...}，等于把「配置写错」翻译成「关闭安全上限」，
     * 一个失误就能让证书链接签成任意时长且毫无报错。另有 {@link OssProperties#validate()}
     * 在启动阶段直接拒绝启动，这里是第二道。</p>
     */
    @Test
    void presignMaxTtl_zeroOrNegative_failsClosed_notUnlimited() {
        for (int bad : new int[]{0, -1}) {
            OssProperties p = worstCaseProperties();
            p.setPresignMaxTtlSeconds(bad);
            VolcTosFileStorageService tos = new VolcTosFileStorageService();
            tos.setProperties(p);
            BusinessException e = assertThrows(BusinessException.class,
                    () -> tos.presignGet(KEY, Duration.ofSeconds(60)),
                    "上限配成 " + bad + " 时必须拒绝签发，而不是放行任意时长");
            assertTrue(e.getMessage().contains("上限配置非法"), e.getMessage());
        }
    }

    /** 启动期就该拦下非法上限，别等到线上第一次下载证书才发现。 */
    @Test
    void ossProperties_rejectsNonPositiveMaxTtlAtStartup() {
        OssProperties p = new OssProperties();
        p.setPresignMaxTtlSeconds(0);
        IllegalStateException e = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(e.getMessage().contains("presign-max-ttl-seconds"), e.getMessage());

        p.setPresignMaxTtlSeconds(300);
        assertDoesNotThrow(p::validate);
    }

    // ================= 回读对象的大小上限 =================

    /**
     * <b>上限守卫本身的口径</b>：超限拒绝、等于上限放行、上限配错则 fail-closed。
     *
     * <p>这一层为什么重要：{@code download} 是<b>服务端主动发起</b>的读取（证书渲染要把样本 PDF
     * 当底图套印），没有任何前端限流挡得住。桶里若有一份几百 MB 的文件——误传，或被替换——
     * 一次读取就能把应用打到 OOM。</p>
     */
    @Test
    void downloadLimit_rejectsOversize_allowsExact_andFailsClosedOnBadConfig() {
        final long max = 1024L;
        assertDoesNotThrow(() -> FileStorageService.requireWithinDownloadLimit(max, max, KEY),
                "正好等于上限应放行");
        assertDoesNotThrow(() -> FileStorageService.requireWithinDownloadLimit(0, max, KEY));

        BusinessException over = assertThrows(BusinessException.class,
                () -> FileStorageService.requireWithinDownloadLimit(max + 1, max, KEY));
        assertTrue(over.getMessage().contains("对象过大"), over.getMessage());

        // 上限配成 0/负数时必须拒绝读取，而不是当成「不限」——
        // 与 presign 上限那条是同一个 fail-closed 口径，两处不该有差别
        for (long bad : new long[]{0L, -1L}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> FileStorageService.requireWithinDownloadLimit(1, bad, KEY),
                    "上限配成 " + bad + " 时必须拒绝读取");
            assertTrue(e.getMessage().contains("上限配置非法"), e.getMessage());
        }
    }

    /** 未启用真实存储时 {@code download} 必须<b>明确失败</b>，不能返回空数组当底图。 */
    @Test
    void disabled_download_failsLoudly_insteadOfReturningEmptyBytes() {
        OssProperties p = new OssProperties();
        p.setEnabled(false);
        for (FileStorageService svc : new FileStorageService[]{
                aliyunWith(p), volcWith(p)}) {
            BusinessException e = assertThrows(BusinessException.class, () -> svc.download(KEY));
            assertTrue(e.getMessage().contains("未启用"), e.getMessage());
        }
    }

    private static AliyunOssFileStorageService aliyunWith(OssProperties p) {
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(p);
        return svc;
    }

    private static VolcTosFileStorageService volcWith(OssProperties p) {
        VolcTosFileStorageService svc = new VolcTosFileStorageService();
        svc.setProperties(p);
        return svc;
    }

    // ================= 未启用时的行为 =================

    @Test
    void disabled_uploadPrivate_returnsKeyNotPlaceholderUrl() {
        OssProperties p = new OssProperties();
        p.setEnabled(false);
        AliyunOssFileStorageService svc = new AliyunOssFileStorageService();
        svc.setProperties(p);

        // 普通 upload 在未启用时返回占位「URL」，私有上传则必须返回 key——
        // 否则调用方把占位串当 key 存库，真启用后取不回文件。
        assertEquals(KEY, svc.uploadPrivate(PDF, KEY, "application/pdf"));
        assertTrue(svc.upload(PDF, KEY, "application/pdf").startsWith("[oss-disabled]"));
    }
}
