package com.hengde.donate.constant;

/**
 * 捐赠证书的业务来源键（{@code honor_certificate.biz_ref}）——<b>形状只在这里定义</b>。
 *
 * <p>协会 2026-10-16（答复五 乙-2）：「所有捐赠和活动都有证书」，时机按我们的建议——
 * 结对在<b>结对成功</b>时出（乙-1）、微心愿在<b>已实现</b>时出、公益捐书与众筹捐物在<b>物资核对合格</b>时出；
 * 众筹捐款在<b>钱到账</b>时出（只有到账的钱才配盖章说「捐了多少」）。</p>
 *
 * <p>每一类的键指向「那一次捐赠」的那一行：结对登记、认领（不是心愿——心愿会被别人重新认领）、
 * 运单（一次寄出一张，与「本次活动数据」同一个粒度）、捐款记录。honor 的唯一键是「类型 + 来源键」，
 * 所以同一次捐赠再怎么重复触发也只有一张证书。</p>
 *
 * @author hengde
 */
public final class DonateCertRefs {

    private DonateCertRefs() {
    }

    public static final String PAIR = "pair:";
    public static final String WISH = "wish:";
    public static final String BOOK = "book:";
    public static final String CROWDFUND_GOODS = "cf-goods:";
    public static final String CROWDFUND_MONEY = "cf-money:";

    /** 证书分类（小程序「协会证书」页签，原型 P83）。 */
    public static final String CATEGORY_PAIR = "pair";
    public static final String CATEGORY_WISH = "wish";
    public static final String CATEGORY_BOOK = "book";
    public static final String CATEGORY_CROWDFUND = "crowdfund";

    public static String pair(Long pairRecordId) {
        return PAIR + pairRecordId;
    }

    public static String wish(Long claimId) {
        return WISH + claimId;
    }

    public static String book(Long shipmentId) {
        return BOOK + shipmentId;
    }

    public static String crowdfundGoods(Long shipmentId) {
        return CROWDFUND_GOODS + shipmentId;
    }

    public static String crowdfundMoney(Long donationId) {
        return CROWDFUND_MONEY + donationId;
    }

    /** 运单的来源键：只有捐书活动与众筹捐物出证（微心愿的包裹归到认领上，在「已实现」时出）。 */
    public static String ofShipment(int bizType, Long shipmentId) {
        if (bizType == DonateFlow.BIZ_BOOK_CAMPAIGN) {
            return book(shipmentId);
        }
        if (bizType == DonateFlow.BIZ_CROWDFUND_GOODS) {
            return crowdfundGoods(shipmentId);
        }
        return null;
    }

    /**
     * 后台人工补发用的「类别 + id」→ 来源键；类别不认识返回 null。
     *
     * @param kind pair / wish / book / crowdfund-goods / crowdfund-money
     */
    public static String of(String kind, Long id) {
        if (kind == null || id == null) {
            return null;
        }
        return switch (kind) {
            case "pair" -> pair(id);
            case "wish" -> wish(id);
            case "book" -> book(id);
            case "crowdfund-goods" -> crowdfundGoods(id);
            case "crowdfund-money" -> crowdfundMoney(id);
            default -> null;
        };
    }

    /** 来源键 → 证书分类；不是捐赠证书的键返回 null。众筹的捐物与捐款同属「项目众筹」一类。 */
    public static String categoryOf(String bizRef) {
        if (bizRef == null) {
            return null;
        }
        if (bizRef.startsWith(PAIR)) {
            return CATEGORY_PAIR;
        }
        if (bizRef.startsWith(WISH)) {
            return CATEGORY_WISH;
        }
        if (bizRef.startsWith(BOOK)) {
            return CATEGORY_BOOK;
        }
        if (bizRef.startsWith(CROWDFUND_GOODS) || bizRef.startsWith(CROWDFUND_MONEY)) {
            return CATEGORY_CROWDFUND;
        }
        return null;
    }

    /** 分类 → 来源键前缀（用于按分类筛选）；众筹两类共用 {@code cf-} 前缀。不认识返回 null。 */
    public static String prefixOfCategory(String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case CATEGORY_PAIR -> PAIR;
            case CATEGORY_WISH -> WISH;
            case CATEGORY_BOOK -> BOOK;
            case CATEGORY_CROWDFUND -> "cf-";
            default -> null;
        };
    }

    public static String categoryLabel(String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case CATEGORY_PAIR -> "助学助困";
            case CATEGORY_WISH -> "微心愿";
            case CATEGORY_BOOK -> "公益捐书";
            case CATEGORY_CROWDFUND -> "项目众筹";
            default -> null;
        };
    }

    /** 从来源键里取出数字 id；格式不对返回 null。 */
    public static Long idOf(String bizRef, String prefix) {
        if (bizRef == null || prefix == null || !bizRef.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.valueOf(bizRef.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
