package com.hengde.donate.constant;

/**
 * 结对与众筹的状态码（与 V52 表注释一致）。
 *
 * <p><b>本批不碰支付</b>：结对登记只是「我认捐这么多」，由协会确认「结对成立」。
 * 成立那一刻是证书的触发点（《协会待确认清单-v3》⑨ 的默认口径），
 * 也是项目「已认捐金额」的计入点——不是到账额，见 V52 文件头。</p>
 *
 * @author hengde
 */
public final class PairFlow {

    private PairFlow() {
    }

    // ================= 结对项目 =================

    /** 草稿（志愿者端不可见）。 */
    public static final int PROJECT_DRAFT = 0;
    /** 进行中（可登记结对）。 */
    public static final int PROJECT_OPEN = 1;
    /** 已结对（认捐额达标或协会手动置；志愿者端「结对成功」页签）。 */
    public static final int PROJECT_PAIRED = 2;
    /** 已结束。 */
    public static final int PROJECT_ENDED = 3;

    // ================= 项目类型 =================

    public static final int TYPE_STUDY = 1;
    public static final int TYPE_POVERTY = 2;
    public static final int TYPE_DISABILITY = 3;

    // ================= 结对登记 =================

    /** 已登记待协会确认。 */
    public static final int PAIR_REGISTERED = 0;
    /** 结对成立（出证的触发点）。 */
    public static final int PAIR_ESTABLISHED = 1;
    /** 已取消（本人撤回或协会取消）。 */
    public static final int PAIR_CANCELLED = 2;

    /** 认捐方式。 */
    public static final int AMOUNT_PARTIAL = 1;
    public static final int AMOUNT_FULL = 2;

    // ================= 众筹项目 =================

    public static final int CROWDFUND_DRAFT = 0;
    public static final int CROWDFUND_OPEN = 1;
    public static final int CROWDFUND_ENDED = 2;

    public static String projectLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PROJECT_DRAFT -> "草稿";
            case PROJECT_OPEN -> "进行中";
            case PROJECT_PAIRED -> "已结对";
            case PROJECT_ENDED -> "已结束";
            default -> "未知";
        };
    }

    public static String typeLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case TYPE_STUDY -> "结对助学";
            case TYPE_POVERTY -> "结对助困";
            case TYPE_DISABILITY -> "结对助残";
            default -> "未知";
        };
    }

    public static boolean isValidType(Integer t) {
        return t != null && (t == TYPE_STUDY || t == TYPE_POVERTY || t == TYPE_DISABILITY);
    }

    public static String pairLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PAIR_REGISTERED -> "待确认";
            case PAIR_ESTABLISHED -> "结对成立";
            case PAIR_CANCELLED -> "已取消";
            default -> "未知";
        };
    }

    public static String crowdfundLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case CROWDFUND_DRAFT -> "草稿";
            case CROWDFUND_OPEN -> "进行中";
            case CROWDFUND_ENDED -> "已结束";
            default -> "未知";
        };
    }

    /** 证书的业务来源键（写进 {@code honor_certificate.biz_ref}）。 */
    public static String certBizRef(Long pairRecordId) {
        return "pair:" + pairRecordId;
    }
}
