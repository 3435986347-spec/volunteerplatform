package com.hengde.honor.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书展示行（志愿者端「我的证书」与后台汇总共用）。
 *
 * <p>形态对应原型 P83 的卡片：活动名称、活动日期、证书编号。
 * <b>另带场次信息</b>——一场活动一个证书，同一活动的多张证书只有场次不同，不带就分不清。</p>
 *
 * @author hengde
 */
@Data
public class CertificateVO {

    private Long id;

    /** 证书编号（P83 卡片「证书编号」） */
    private String certNo;

    /** 类型 1活动证书/2i志愿证书 */
    private Integer type;

    private Long volunteerId;
    /** 志愿者姓名（后台汇总展示；志愿者端可空） */
    private String volunteerName;

    private Long activityId;
    private String activityTitle;

    /** 场次 id */
    private Long slotId;
    /** 场次（岗位）名称 */
    private String slotProjectName;
    private LocalDateTime slotStartTime;
    private LocalDateTime slotEndTime;

    /**
     * 证书来源的一句话说明：捐赠证书＝结对项目名。
     *
     * <p>活动证书为空——它有 {@code activityTitle} / {@code slotProjectName}。
     * 不复用 {@code activityTitle} 装项目名：前端按字段名理解含义，
     * 把一个结对项目塞进「活动名」里迟早被读成「他参加过这个活动」。</p>
     */
    private String sourceTitle;

    /** 业务来源键（捐赠证书＝{@code pair:{结对登记id}}；活动证书为空） */
    private String bizRef;

    /** 来源 1系统生成/2后台批量上传/3i志愿导出上传 */
    private Integer source;

    /** 下载次数 */
    private Integer downloadCount;

    /**
     * PDF 是否已就绪。
     *
     * <p><b>不暴露 {@code fileKey}</b>：对象 key 可枚举、会进日志，不是秘密但也没有给前端的必要；
     * 前端只需要知道「能不能点下载」，真要取文件走 {@code /file} 换短期签名 URL。</p>
     */
    private Boolean fileReady;

    /** PDF 渲染完成时间；懒渲染前为 null */
    private LocalDateTime generateTime;

    /** 证书权益创建时间（＝秘书部确认那一刻，志愿者视角的「拿到证书」时间） */
    private LocalDateTime createTime;

    /**
     * 是否已软删。
     *
     * <p><b>只在后台列表 {@code includeDeleted=true} 时才可能为 true</b>；
     * 志愿者端永远查不到软删的行。后台需要它来区分展示并给出「撤销删除」入口。</p>
     */
    private Boolean deleted;

    /** 删除原因（后台展示；未删为 null） */
    private String deletedReason;

    /** 删除时间（后台展示；未删为 null） */
    private LocalDateTime deletedTime;
}
