package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品出参（志愿者端与管理端共用；管理端多看到审核字段）。
 *
 * @author hengde
 */
@Data
@Schema(description = "积分商品")
public class MallGoodsVO {

    @Schema(description = "商品 id")
    private Long id;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "商品图片")
    private String coverUrl;

    @Schema(description = "商品详情（图文）")
    private String detail;

    @Schema(description = "赞助方名称")
    private String sponsorName;

    @Schema(description = "赞助企业 id；为空＝平台自营。非空时赞助方名称是企业名称快照，后台修改商品不会改它（仅管理端）")
    private Long sponsorEnterpriseId;

    @Schema(description = "必须使用的卷定义 id；为空=不要求（Row 8 F「没有卷就不能兑换」）")
    private Long requireCouponId;

    @Schema(description = "必须使用的卷名称")
    private String requireCouponName;

    @Schema(description = "状态码 0草稿/1待审核/2已上架/3已停用/4驳回")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusLabel;

    @Schema(description = "隐藏 0否/1是")
    private Integer hidden;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "规格列表（库存与所需积分都在这里）")
    private List<MallGoodsSpecVO> specs;

    // ---- 领取方式（V95）：下面这些是【生效值】，没按新口径设置过的商品按全局行为折算好了，前端照着显示即可 ----

    @Schema(description = "可自提（生效值）")
    private Boolean allowPickup;

    @Schema(description = "可快递（生效值）")
    private Boolean allowExpress;

    @Schema(description = "快递费【分】（现金；不收现金快递费时为 null）")
    private Integer expressFeeFen;

    @Schema(description = "快递费【元】")
    private String expressFeeYuan;

    @Schema(description = "运费积分（不收积分运费时为 null）")
    private Integer expressPoints;

    @Schema(description = "可选的自提点（只含启用中的；空=不用选，到配置里的唯一自提点领）")
    private List<MallPickupPointVO> pickupPoints;

    @Schema(description = "虚拟商品")
    private Boolean virtualGoods;

    @Schema(description = "虚拟商品的兑换二维码图片（只在后台与企业端回填；志愿者在兑换成功后的兑换详情里拿到）")
    private String redeemQrUrl;

    @Schema(description = "是否已按新口径设置过领取方式（后台用：false 表示还在沿用全局行为）")
    private Boolean deliveryConfigured;

    @Schema(description = "已选自提点 id（后台编辑回填用，含已停用的）")
    private List<Long> pickupPointIds;

    /** 各规格里最低的所需积分，列表页展示「XX 积分起」用；无规格时为 null。 */
    @Schema(description = "最低所需积分")
    private Integer minPoints;

    /** 各规格库存之和；志愿者端据此显示「已兑完」。 */
    @Schema(description = "总库存")
    private Integer totalStock;

    @Schema(description = "评价条数")
    private Integer reviewCount;

    @Schema(description = "平均评分，一位小数；无评价时为 null")
    private Double avgRating;

    // ---- 以下仅管理端返回 ----

    @Schema(description = "驳回原因（仅管理端）")
    private String rejectReason;

    @Schema(description = "审核人（仅管理端）")
    private Long reviewBy;

    @Schema(description = "审核时间（仅管理端）")
    private LocalDateTime reviewTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
