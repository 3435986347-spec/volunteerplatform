package com.hengde.donate.constant;

/**
 * 物资流转的状态码与动作码（与 V50 各表注释一致），收在一处——运单 / 物资 / 箱子 / 轨迹四张表的码
 * 彼此相关（到货要同时改运单与物资、送达要同时改箱子与物资），分散在四个类里读的人要来回跳。
 *
 * @author hengde
 */
public final class DonateFlow {

    private DonateFlow() {
    }

    // ================= 来源类型（运单 / 物资 / 箱子的 biz_type） =================

    /** 公益捐书活动（本批）。 */
    public static final int BIZ_BOOK_CAMPAIGN = 1;
    /** 圆梦微心愿（微心愿批预留）。 */
    public static final int BIZ_WISH = 2;
    /** 众筹捐物（捐款批预留）。 */
    public static final int BIZ_CROWDFUND_GOODS = 3;

    // ================= 捐书活动 =================

    public static final int CAMPAIGN_DRAFT = 0;
    public static final int CAMPAIGN_PUBLISHED = 1;
    public static final int CAMPAIGN_ENDED = 2;

    // ================= 运单 =================

    /** 已寄出（捐赠人登记快递单号即为此态）。 */
    public static final int SHIPMENT_SHIPPED = 1;
    /** 已到货（机构扫码确认）。 */
    public static final int SHIPMENT_ARRIVED = 2;
    /** 已核对（逐件判定合格 / 不合格）。 */
    public static final int SHIPMENT_CHECKED = 3;
    /** 已取消（到货之前捐赠人可取消）。 */
    public static final int SHIPMENT_CANCELLED = 4;

    // ---- 快递100 订阅（V56，物流推送批）。3、4 交还给轮询 ----
    /** 待订阅 */
    public static final int SUBSCRIBE_PENDING = 0;
    /** 订阅中：由推送保持快照，轮询跳过 */
    public static final int SUBSCRIBE_ACTIVE = 1;
    /** 推送结束：快递100 判定到终态 */
    public static final int SUBSCRIBE_FINISHED = 2;
    /** 被快递100 中止（如长时间无轨迹）——交还轮询 */
    public static final int SUBSCRIBE_ABORTED = 3;
    /** 放弃订阅（被拒或失败次数用尽）——交还轮询 */
    public static final int SUBSCRIBE_GAVE_UP = 4;

    /** 退回：无需退回。 */
    public static final int RETURN_NONE = 0;
    /** 退回：有不合格物资，等捐赠人提交收件信息（Row 17 D「退回的话需要志愿者提交收件信息」）。 */
    public static final int RETURN_AWAIT_ADDRESS = 1;
    /** 退回：收件信息已交，等我们寄回。 */
    public static final int RETURN_AWAIT_SHIP = 2;
    /** 退回：已寄回（Row 17 D「我们这边会给他上传快递单号」）。 */
    public static final int RETURN_SHIPPED = 3;

    // ================= 物资 =================

    public static final int ITEM_PENDING = 0;
    public static final int ITEM_ARRIVED = 1;
    public static final int ITEM_QUALIFIED = 2;
    public static final int ITEM_REJECTED = 3;
    public static final int ITEM_RETURNED = 4;
    public static final int ITEM_PACKED = 5;
    public static final int ITEM_DELIVERED = 6;
    public static final int ITEM_CANCELLED = 7;

    // ================= 物资类型（Row 17 C「本次活动数据」三类 + 其他） =================

    public static final int TYPE_BOOK = 1;
    public static final int TYPE_STATIONERY = 2;
    public static final int TYPE_SPORTS = 3;
    public static final int TYPE_OTHER = 9;

    // ================= 箱子 =================

    public static final int BOX_PACKING = 0;
    public static final int BOX_DELIVERED = 1;

    // ================= 轨迹动作 =================

    public static final int ACT_REGISTER = 1;
    public static final int ACT_ARRIVE = 2;
    public static final int ACT_CHECK_PASS = 3;
    public static final int ACT_CHECK_FAIL = 4;
    public static final int ACT_CODE = 5;
    public static final int ACT_PACK = 6;
    public static final int ACT_UNPACK = 7;
    public static final int ACT_DELIVER = 8;
    public static final int ACT_RETURN_SHIP = 9;
    public static final int ACT_ADMIN_ADD = 10;
    public static final int ACT_ADMIN_REMOVE = 11;
    public static final int ACT_CANCEL = 12;
    public static final int ACT_RETURN_ADDRESS = 13;

    /** 轨迹操作方。 */
    public static final int OP_SYSTEM = 0;
    public static final int OP_ADMIN = 1;
    public static final int OP_DONOR = 2;

    public static boolean isValidItemType(Integer t) {
        return t != null && (t == TYPE_BOOK || t == TYPE_STATIONERY || t == TYPE_SPORTS || t == TYPE_OTHER);
    }

    public static String itemTypeLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case TYPE_BOOK -> "课外书籍";
            case TYPE_STATIONERY -> "学习用品";
            case TYPE_SPORTS -> "运动器材";
            case TYPE_OTHER -> "其他";
            default -> "未知";
        };
    }

    public static String shipmentLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case SHIPMENT_SHIPPED -> "已寄出";
            case SHIPMENT_ARRIVED -> "已到货";
            case SHIPMENT_CHECKED -> "已核对";
            case SHIPMENT_CANCELLED -> "已取消";
            default -> "未知";
        };
    }

    public static String subscribeLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case SUBSCRIBE_PENDING -> "待订阅";
            case SUBSCRIBE_ACTIVE -> "订阅中";
            case SUBSCRIBE_FINISHED -> "推送结束";
            case SUBSCRIBE_ABORTED -> "被中止（轮询兜底）";
            case SUBSCRIBE_GAVE_UP -> "已放弃（轮询兜底）";
            default -> "未知";
        };
    }

    public static String returnLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case RETURN_NONE -> "无需退回";
            case RETURN_AWAIT_ADDRESS -> "待提交退回收件信息";
            case RETURN_AWAIT_SHIP -> "待寄回";
            case RETURN_SHIPPED -> "已寄回";
            default -> "未知";
        };
    }

    public static String itemLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case ITEM_PENDING -> "待到货";
            case ITEM_ARRIVED -> "已到货待核对";
            case ITEM_QUALIFIED -> "合格";
            case ITEM_REJECTED -> "不合格待退回";
            case ITEM_RETURNED -> "已退回";
            case ITEM_PACKED -> "已装箱";
            case ITEM_DELIVERED -> "已送达";
            case ITEM_CANCELLED -> "已取消";
            default -> "未知";
        };
    }

    /**
     * 「审核状态」（Row 38 捐书记录要展示）——对捐赠人来说只有三种：还没核对 / 合格 / 不合格。
     * 装箱、送达都是合格之后的事，不该让「已装箱」看起来像另一种审核结论。
     */
    public static String auditLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case ITEM_PENDING, ITEM_ARRIVED -> "待核对";
            case ITEM_QUALIFIED, ITEM_PACKED, ITEM_DELIVERED -> "合格";
            case ITEM_REJECTED, ITEM_RETURNED -> "不合格";
            case ITEM_CANCELLED -> "已取消";
            default -> "未知";
        };
    }

    /** 物资「目前进度」（Row 17 F 搜索与导出列）。 */
    public static String progressLabel(Integer itemStatus) {
        return itemLabel(itemStatus);
    }
}
