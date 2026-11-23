package com.hengde.honor.constant;

/**
 * 证书类型。
 *
 * <p>原型 P83「协会证书」按类别分页签：志愿活动／助学助困／微心愿／公益捐书。
 * <b>本批只放行「活动证书」这一类</b>——后四类的数据源在 {@code donate} 域（V3 未建），
 * i志愿证书属第 4B 批。类型维度先建好，但不预置没有数据源的编码。</p>
 *
 * @author hengde
 */
public final class CertificateType {

    private CertificateType() {
    }

    /** 活动证书：参加完活动后自动生成（xlsx Row 36） */
    public static final int ACTIVITY = 1;

    /**
     * i志愿服务证书（xlsx Row 37）。
     *
     * <p><b>第 4B 批</b>——本批不产生此类记录。列在这里是因为
     * {@code honor_certificate} 是证书的唯一主体，4B 上线时只共用这张表作文件归属。</p>
     */
    public static final int IVOL = 2;

    /**
     * 捐赠证书（xlsx Row 10「捐赠后需要自动生成证书」，V3 结对批）。
     *
     * <p>没有活动与场次，故 {@code uk_slot_cert} 对它<b>不起作用</b>（多个 NULL 互不相同）——
     * 幂等靠 V54 的 {@code uk_cert_biz_ref}（{@code biz_ref = pair:{结对登记id}}）。</p>
     *
     * <p><b>触发点是「结对成立」</b>（《协会待确认清单-v3》⑨ 的默认；协会若改成「钱到账才出证」，
     * 挪的是挂钩的位置——权益与渲染这一半不用动）。</p>
     */
    public static final int DONATION = 3;
}
