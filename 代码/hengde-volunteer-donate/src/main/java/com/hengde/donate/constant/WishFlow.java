package com.hengde.donate.constant;

/**
 * 微心愿与认领的状态码（与 V51 表注释一致）。
 *
 * <p><b>两张表的状态是联动的</b>：认领成功 = 心愿「待认领 → 已认领」+ 插一条「认领中」；
 * 取消 / 撤销 = 认领「认领中 → 已取消 / 已撤销」+ 心愿回到「待认领」；实现 = 认领「认领中 → 已实现」+
 * 心愿「已认领 → 已实现」。每一对都在同一事务里、各自 CAS。</p>
 *
 * @author hengde
 */
public final class WishFlow {

    private WishFlow() {
    }

    // ================= 心愿 =================

    /** 待认领（心愿池）。 */
    public static final int WISH_OPEN = 0;
    /** 已认领。 */
    public static final int WISH_CLAIMED = 1;
    /** 已实现。 */
    public static final int WISH_REALIZED = 2;
    /** 已下架（后台撤下，未认领时才能下架）。 */
    public static final int WISH_TAKEN_DOWN = 3;

    // ================= 认领 =================

    public static final int CLAIM_ACTIVE = 0;
    public static final int CLAIM_REALIZED = 1;
    public static final int CLAIM_CANCELLED = 2;
    public static final int CLAIM_REVOKED = 3;

    public static String wishLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case WISH_OPEN -> "待认领";
            case WISH_CLAIMED -> "已认领";
            case WISH_REALIZED -> "已实现";
            case WISH_TAKEN_DOWN -> "已下架";
            default -> "未知";
        };
    }

    public static String claimLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case CLAIM_ACTIVE -> "认领中";
            case CLAIM_REALIZED -> "已实现";
            case CLAIM_CANCELLED -> "已取消";
            case CLAIM_REVOKED -> "已撤销";
            default -> "未知";
        };
    }

    /**
     * 「关键信息需要自动打*号」（Row 12 C）：姓名只留第一个字。
     *
     * <p>放在这里而不是前端：认领之前服务端就<b>不下发</b>明文——前端不显示不等于没泄露。</p>
     */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 1));
    }

    /** 学校只留前两个字（通常是县 / 镇名，定位不到具体哪所），其余打 *。 */
    public static String maskSchool(String school) {
        if (school == null || school.isEmpty()) {
            return school;
        }
        int keep = Math.min(2, school.length());
        return school.substring(0, keep) + "***";
    }
}
