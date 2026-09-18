package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 协会证书（V31，第 4 批·电子证书核心）。
 *
 * <p>需求原文 xlsx Row 36 C：「参加完活动后，<b>自动生成一个盖章的电子证书</b>……电子证书预览和下载」。
 * 故本行在<b>秘书部确认考勤</b>那一刻就写入（志愿者当即在「我的证书」看到条目），
 * 而 PDF 到首次预览/下载时才渲染并回填 {@link #fileKey}。</p>
 *
 * <p><b>一人一场次一张</b>——协会 2026-07-30：「志愿者证书是根据他的<b>场次</b>来决定的，
 * 一场活动一个证书」，由 {@code uk_slot_cert(type, volunteer_id, slot_id)} 保证。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_certificate")
public class HonorCertificate extends BaseEntity {

    /** 证书编号，对外展示（原型 P83 卡片「证书编号：564641541665156」） */
    private String certNo;

    /** 归属志愿者 volunteer.id */
    private Long volunteerId;

    /** 类型，见 {@link com.hengde.honor.constant.CertificateType} */
    private Integer type;

    /** 所属活动 activity.id；i志愿证书为 null */
    private Long activityId;

    /** 所属场次 activity_slot.id；i志愿证书为 null */
    private Long slotId;

    /**
     * 证书 PDF 的<b>私有对象 key</b>；null = 尚未渲染。
     *
     * <p><b>不是 URL</b>：证书走私有对象 + 短期签名 URL，签名 URL 在有效期内等同凭证，绝不能落库。</p>
     */
    private String fileKey;

    /** 来源，见 {@link com.hengde.honor.constant.CertificateSource} */
    private Integer source;

    /** 下载次数（Row 37 F 列「下载次数」） */
    private Integer downloadCount;

    /** PDF 实际渲染完成时间；懒渲染前为 null */
    private LocalDateTime generateTime;

    /**
     * 业务来源键，形如 {@code pair:{结对登记id}}；活动证书为 null（它由 {@code uk_slot_cert} 保幂等）。
     *
     * <p>捐赠证书没有活动与场次，{@code uk_slot_cert} 对它不起作用（MySQL 视多个 NULL 互不相同），
     * 幂等改由 V54 的 {@code uk_cert_biz_ref} 保证。</p>
     */
    private String bizRef;

    /**
     * 「类型 + 业务来源键」，<b>由数据库生成列计算</b>，应用不可写（写它 INSERT 直接报错）。
     * 软删行仍占用——与 {@code uk_slot_cert} 同口径：重复触发要能命中并恢复原件，而不是另发一张新编号。
     */
    @TableField(value = "active_biz_ref",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private String activeBizRef;

    /** 删除人 admin_user.id */
    private Long deletedBy;

    /** 删除时间 */
    private LocalDateTime deletedTime;

    /** 删除原因 */
    private String deletedReason;
}
