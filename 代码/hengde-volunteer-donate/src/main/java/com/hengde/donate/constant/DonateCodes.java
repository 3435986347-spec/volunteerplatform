package com.hengde.donate.constant;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 物资流转的三级条码（Row 17 第 7 / 9 步）——<b>前缀与形态的单一来源</b>，仿 {@code AttendanceQr}。
 *
 * <ul>
 *   <li><b>物品专属码</b> {@code HDI} + 10 位：Row 17「给每一个捐赠物品生成一个专属条形码」；</li>
 *   <li><b>箱码</b> {@code HDB} + 8 位：Row 17「生成箱子条形码，把物品扫码装入箱子」；</li>
 *   <li><b>快递单号</b>：快递公司的码，不是我们生成的，不在这里。</li>
 * </ul>
 *
 * <p><b>字母表去掉 0 1 I O</b>（与取货码 {@code PickupCodeUtil} 同一张表）：码要印在标签上被人抄、被扫码枪扫，
 * 去掉易混字符后输入侧不需要任何纠错映射。</p>
 *
 * <p><b>为什么带固定前缀</b>：扫码页只有一个输入框，扫出来的可能是专属码、箱码、快递单号、商品条码中的任何一种。
 * 前缀让 {@link #kindOf} 不查库就能分流，也让「拿箱码去装箱子」这种错位当场被拒。
 * {@code HD} 开头的快递单号在国内主流快递里没有，撞不上。</p>
 *
 * <p>随机源用 {@link SecureRandom}：物品码不是持有者凭据，可预测本身不致命；但它会被印在对外可见的标签上，
 * 可预测的码等于公开了「下一件物品的编号」，没有理由为省一点性能留这个口子。</p>
 *
 * @author hengde
 */
public final class DonateCodes {

    private DonateCodes() {
    }

    /** 物品专属码前缀。 */
    public static final String ITEM_PREFIX = "HDI";

    /** 箱码前缀。 */
    public static final String BOX_PREFIX = "HDB";

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int ITEM_RANDOM = 10;
    private static final int BOX_RANDOM = 8;
    private static final Pattern ITEM = Pattern.compile("^HDI[2-9A-HJ-NP-Z]{" + ITEM_RANDOM + "}$");
    private static final Pattern BOX = Pattern.compile("^HDB[2-9A-HJ-NP-Z]{" + BOX_RANDOM + "}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 扫出来的码是哪一种。 */
    public enum Kind {
        /** 物品专属码 */
        ITEM,
        /** 箱码 */
        BOX,
        /** 不是我们生成的码：可能是快递单号或商品条码，要查库才知道 */
        EXTERNAL
    }

    public static String newItemCode() {
        return ITEM_PREFIX + random(ITEM_RANDOM);
    }

    public static String newBoxCode() {
        return BOX_PREFIX + random(BOX_RANDOM);
    }

    /** 心愿编号（导入 / 上传时没给编号的由系统生成）。不参与扫码识别——心愿编号印在面单上给人看，不是给扫码枪扫的。 */
    public static String newWishNo() {
        return "HDW" + random(BOX_RANDOM);
    }

    /** 扫码枪 / 手输的码统一成大写、去空白。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static Kind kindOf(String normalized) {
        if (normalized != null && ITEM.matcher(normalized).matches()) {
            return Kind.ITEM;
        }
        if (normalized != null && BOX.matcher(normalized).matches()) {
            return Kind.BOX;
        }
        return Kind.EXTERNAL;
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
