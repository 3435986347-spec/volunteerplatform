package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 物资流转的出参。放在一个文件里的理由同 {@code BoxDTOs}：它们是同一张流水线上的几个视图，
 * 字段互相引用（运单里有物资、物资里有轨迹），分散在十个文件里读的人要来回跳。
 *
 * @author hengde
 */
public final class DonateFlowVOs {

    private DonateFlowVOs() {
    }

    /** 「本次活动数据」（Row 17 C）。 */
    @Data
    @Schema(description = "本次活动数据")
    public static class CampaignStats {
        @Schema(description = "参加人数")
        private long participants;
        @Schema(description = "收到包裹")
        private long parcels;
        @Schema(description = "收到课外书籍（件）")
        private long books;
        @Schema(description = "收到学习用品（件）")
        private long stationery;
        @Schema(description = "收到运动器材（件）")
        private long sports;
        @Schema(description = "统计截止时间；为空=不截止")
        private LocalDateTime statsDeadline;
    }

    /** 捐书活动。 */
    @Data
    @Schema(description = "捐书活动")
    public static class Campaign {
        private Long id;
        private String title;
        private String coverUrl;
        @Schema(description = "详情（仅详情接口返回）")
        private String detail;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        @Schema(description = "库里的状态 0草稿/1已发布/2已结束")
        private Integer status;
        @Schema(description = "展示状态 0未开始/1进行中/2已结束（按时间现算）；草稿为空")
        private Integer displayStatus;
        @Schema(description = "展示状态中文名")
        private String displayLabel;
        @Schema(description = "此刻能不能报名")
        private Boolean open;
        private CampaignStats stats;
        @Schema(description = "收件人（= 当前捐赠人姓名；仅志愿者端详情返回）")
        private String recvName;
        @Schema(description = "收件电话")
        private String recvPhone;
        @Schema(description = "收件地址（志愿者端 = 前缀 + 捐赠人姓名；后台 = 前缀本身）")
        private String recvAddress;
        private LocalDateTime createTime;
    }

    /** 一条轨迹。 */
    @Data
    @Schema(description = "流转轨迹")
    public static class Trace {
        private LocalDateTime time;
        private Integer action;
        private String content;
        @Schema(description = "关联物资 id；运单级动作为空")
        private Long itemId;
    }

    /** 一件物资。 */
    @Data
    @Schema(description = "捐赠物资")
    public static class Item {
        private Long id;
        private Long shipmentId;
        private String name;
        private Integer itemType;
        private String itemTypeLabel;
        private Integer quantity;
        @Schema(description = "商品条码（Row 38「编码」）")
        private String catalogBarcode;
        @Schema(description = "物品专属码（Row 38「编号」）；核对合格后才有")
        private String exclusiveCode;
        private Integer status;
        @Schema(description = "目前进度")
        private String statusLabel;
        @Schema(description = "审核状态（Row 38）：待核对 / 合格 / 不合格")
        private String auditLabel;
        private String checkRemark;
        private String boxCode;
        @Schema(description = "受捐单位（Row 38「受捐学校」，送达时快照）")
        private String recipientOrgName;
        private LocalDateTime deliverTime;
        @Schema(description = "借阅次数（清单 ⑥：只存字段）")
        private Integer borrowCount;
        @Schema(description = "所属活动（我的捐书记录用）")
        private String campaignTitle;
        @Schema(description = "所属运单的快递单号（我的捐书记录用）")
        private String expressNo;
    }

    /** 一个运单。 */
    @Data
    @Schema(description = "捐赠运单")
    public static class Shipment {
        private Long id;
        private Integer bizType;
        private Long bizId;
        private String campaignTitle;
        private String donorName;
        private String donorOrg;
        private String expressCode;
        private String expressCompany;
        private String expressNo;
        private Integer status;
        private String statusLabel;
        private LocalDateTime shipTime;
        private LocalDateTime arriveTime;
        private LocalDateTime checkTime;
        private Integer returnStatus;
        private String returnStatusLabel;
        private String returnName;
        @Schema(description = "退回收件电话（本人与后台可见，明文）")
        private String returnPhone;
        private String returnAddress;
        private String returnExpressCompany;
        private String returnExpressNo;
        private LocalDateTime returnTime;
        @Schema(description = "物流状态码（快递100 快照）")
        private Integer trackState;
        private String trackStateLabel;
        private String trackLastContext;
        private LocalDateTime trackLastTime;
        @Schema(description = "快递100 订阅状态 0待订阅/1订阅中/2推送结束/3被中止/4已放弃（3、4 由轮询兜底）")
        private Integer subscribeStatus;
        private String subscribeStatusLabel;
        @Schema(description = "上次订阅失败 / 被中止的原因")
        private String subscribeError;
        private List<Item> items = new ArrayList<>();
        private List<Trace> traces = new ArrayList<>();
        @Schema(description = "捐赠人 id（仅管理端）")
        private Long donorVolunteerId;
    }

    /** 一只箱子。 */
    @Data
    @Schema(description = "箱子")
    public static class Box {
        private Long id;
        private String boxCode;
        @Schema(description = "箱码条码图 data URL（仅新建与详情返回，供标签打印）")
        private String barcode;
        private Integer bizType;
        private Long bizId;
        private String campaignTitle;
        private Integer status;
        private String statusLabel;
        private String recipientOrgName;
        private LocalDateTime deliverTime;
        private Integer itemCount;
        private List<Item> items = new ArrayList<>();
        private LocalDateTime createTime;
    }

    /** 物品专属码与标签内容（Row 17 第 7–8 步：打印出来，面单上有物品名称）。 */
    @Data
    @Schema(description = "物品专属码与标签")
    public static class ItemLabel {
        private Long itemId;
        private String exclusiveCode;
        @Schema(description = "条码图 data URL")
        private String barcode;
        private String name;
        private String itemTypeLabel;
        private Integer quantity;
        private String donorName;
        private String campaignTitle;
        @Schema(description = "心愿编号（微心愿物资，Row 12 G「面单上有……孩子编号」）")
        private String wishNo;
        @Schema(description = "受捐学生（微心愿物资，Row 12 G「面单上有……受捐学生」）")
        private String childName;
        @Schema(description = "受捐单位（微心愿物资为上报单位）")
        private String recipientOrgName;
    }

    /** 扫码识别结果：扫码页只有一个输入框，先告诉它扫到的是什么。 */
    @Data
    @Schema(description = "扫码识别结果")
    public static class ScanResult {
        @Schema(description = "ITEM 物品专属码 / BOX 箱码 / SHIPMENT 快递单号 / CATALOG 商品条码 / UNKNOWN 不认识")
        private String kind;
        private Long id;
        @Schema(description = "一句话概要")
        private String summary;
        private Item item;
        private Box box;
        private List<Shipment> shipments;
        private String catalogName;
    }

    /** 物流轨迹（快递100 快照）。 */
    @Data
    @Schema(description = "物流轨迹")
    public static class Track {
        @Schema(description = "是否开通了物流查询；false 时只有我们自己的流转轨迹")
        private boolean available;
        @Schema(description = "这次返回的是旧快照（查询失败或未到刷新间隔）")
        private boolean stale;
        private Integer state;
        private String stateLabel;
        private String lastContext;
        private LocalDateTime lastTime;
        private LocalDateTime queryTime;
        private List<TrackNodeVO> nodes = new ArrayList<>();
        private String message;
    }

    /** 物流轨迹上的一个节点。 */
    @Data
    @Schema(description = "物流节点")
    public static class TrackNodeVO {
        private LocalDateTime time;
        private String context;
    }

    /** 快递公司选项。 */
    @Data
    @Schema(description = "快递公司")
    public static class Express {
        private String code;
        private String label;
        private boolean trackable;
    }
}
