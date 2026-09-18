package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 结对与众筹的出参（V3 结对批）。
 *
 * @author hengde
 */
public final class PairVOs {

    private PairVOs() {
    }

    /** 结对项目。 */
    @Data
    @Schema(description = "结对项目")
    public static class Project {
        private Long id;
        private String title;
        private Integer projectType;
        private String projectTypeLabel;
        private String coverUrl;
        @Schema(description = "详情（仅详情接口返回）")
        private String detail;
        @Schema(description = "受助金额（目标）")
        private BigDecimal targetAmount;
        @Schema(description = "已认捐金额——**登记额，不是已到账金额**")
        private BigDecimal pledgedAmount;
        @Schema(description = "已到账金额（捐款批起：结对捐款到账累加、退款减回）——Row 10「已筹多少钱」展示它")
        private BigDecimal raisedAmount;
        @Schema(description = "认捐进度百分比，封顶 100")
        private Integer progressPercent;
        @Schema(description = "参加人数（有效登记人数，现算）")
        private Integer participantCount;
        private Integer status;
        private String statusLabel;
        private LocalDateTime createTime;
        @Schema(description = "我的登记（志愿者端；没有则为空）")
        private PairRecord myPair;
        @Schema(description = "此刻我能不能登记结对")
        private Boolean canRegister;
    }

    /** 一条结对登记。 */
    @Data
    @Schema(description = "结对登记")
    public static class PairRecord {
        private Long id;
        private Long projectId;
        private String projectTitle;
        @Schema(description = "结对人 id（仅管理端）")
        private Long volunteerId;
        @Schema(description = "结对人姓名（仅管理端）")
        private String volunteerName;
        @Schema(description = "结对人电话（仅管理端，明文——协会要联系他）")
        private String volunteerPhone;
        private BigDecimal amount;
        @Schema(description = "已付金额（捐款批起）；认捐额 − 已付 = 还可付多少")
        private BigDecimal paidAmount;
        private Integer amountType;
        private Integer status;
        private String statusLabel;
        private LocalDateTime registerTime;
        @Schema(description = "结对成立时间（证书按它出）")
        private LocalDateTime establishedTime;
        private LocalDateTime cancelTime;
        private String cancelReason;
        private String remark;
    }

    /**
     * 结对中心的一条（Row 34「一个项目为一个记录，进去后会有详细的捐赠信息，需要预留开发票」，V3 收尾批）。
     */
    @Data
    @Schema(description = "结对中心详情：这条结对 + 为它付过的每一笔款（含发票字段）")
    public static class PairCenter {
        private PairRecord record;
        @Schema(description = "还可付多少（认捐额 − 已付；已取消的为 0）")
        private java.math.BigDecimal remainingAmount;
        @Schema(description = "为这条结对发起过的全部捐款，新的在前")
        private List<com.hengde.donate.vo.DonationVOs.Donation> donations = new ArrayList<>();
    }

    /** 一封来信。 */
    @Data
    @Schema(description = "受助方来信")
    public static class Letter {
        private Long id;
        private Long projectId;
        private String projectTitle;
        private String title;
        private String content;
        private List<String> images = new ArrayList<>();
        private LocalDateTime writeTime;
        @Schema(description = "是不是写给我的（私信；false = 项目公开信）")
        private boolean forMe;
        @Schema(description = "收信的结对登记 id（仅管理端）")
        private Long pairRecordId;
        private LocalDateTime createTime;
    }

    /** 众筹项目。 */
    @Data
    @Schema(description = "众筹项目")
    public static class Crowdfund {
        private Long id;
        private String title;
        private String coverUrl;
        @Schema(description = "详情（仅详情接口返回）")
        private String detail;
        @Schema(description = "预计金额（目标）")
        private BigDecimal targetAmount;
        @Schema(description = "已筹金额（到账额：捐款到账累加、退款减回）")
        private BigDecimal raisedAmount;
        @Schema(description = "进度百分比，封顶 100")
        private Integer progressPercent;
        @Schema(description = "捐款人数（已到账的捐款去重现算）")
        private Integer donorCount;
        @Schema(description = "是否接受捐款")
        private Boolean acceptMoney;
        @Schema(description = "是否接受捐物")
        private Boolean acceptGoods;
        @Schema(description = "需要哪些物资")
        private String goodsNeeded;
        @Schema(description = "物资收件人（捐物的人照着寄）")
        private String recvName;
        private String recvPhone;
        private String recvAddress;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer status;
        private String statusLabel;
        private LocalDateTime createTime;
    }
}
