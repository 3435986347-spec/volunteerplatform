package com.hengde.donate.constant;

/**
 * 积分商品的审核 / 上架状态。单一来源，勿在别处重新散落魔法数。
 *
 * <p><b>与 {@code hidden} 正交</b>：隐藏是纯展示开关（Row 8 F「商品隐藏功能」），
 * 走独立入口、<b>不触发重审</b>；本枚举管的是审核态。两者混在一起会让「临时下架一下」
 * 变成「重新走一遍审核」。</p>
 *
 * @author hengde
 */
public final class MallGoodsStatus {

    private MallGoodsStatus() {
    }

    /** 草稿：新建后的初始态，志愿者端不可见 */
    public static final int DRAFT = 0;
    /** 待审核：已提交，等 {@code donate:goods-audit} 处置 */
    public static final int PENDING = 1;
    /** 已上架：唯一可被下单的状态 */
    public static final int ON_SALE = 2;
    /** 已停用：下架，志愿者端不可见；已生成的订单不受影响 */
    public static final int DISABLED = 3;
    /** 已驳回：审核不通过，可改后重新提交 */
    public static final int REJECTED = 4;

    /**
     * 状态中文名，供出参直接展示（前端不必再维护一份映射）。
     *
     * @param status 状态码
     * @return 中文名；未知码返回「其他」而非抛异常——展示层不应因脏数据而整页失败
     */
    public static String labelOf(Integer status) {
        if (status == null) {
            return "其他";
        }
        return switch (status) {
            case DRAFT -> "草稿";
            case PENDING -> "待审核";
            case ON_SALE -> "已上架";
            case DISABLED -> "已停用";
            case REJECTED -> "已驳回";
            default -> "其他";
        };
    }
}
