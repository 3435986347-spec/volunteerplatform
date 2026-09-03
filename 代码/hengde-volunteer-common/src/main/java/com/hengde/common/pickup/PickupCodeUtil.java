package com.hengde.common.pickup;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.qrcode.BarcodeUtil;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 自提取货码的<b>形态</b>：生成、格式校验、条码渲染。<b>被动工具，无 bean/DI</b>（仿 {@code PasswordUtil}）。
 *
 * <h2>为什么只有这三件事</h2>
 *
 * <p>取货码要被两处共用：积分商城的兑换单（V3 商城批）与纸质证书申请单（honor 第 4B 批，当前冻结）。
 * 共用的是<b>码的形态</b>，<b>不是一张共享表</b>——核销时那句
 * {@code UPDATE ... SET status=已领取 WHERE pickup_code=? AND status=待领取} 必须知道打哪张表，
 * 而两边是两张不同的表，各自建各自的 {@code uk_pickup_code}。</p>
 *
 * <p><b>所以本类刻意不持有任何 Mapper，common 至今零 Mapper、零 {@code @TableName}。</b>
 * 如果哪天觉得「这里注入个 Mapper 就方便了」，那正是走偏的信号：
 * 破了这条，纸质证书解冻时两个域会争夺同一张表，而它们的状态机、退款口径、
 * 审核权限点全都不一样。</p>
 *
 * <h2>码的形态</h2>
 *
 * <pre>
 *   PU M 7K3QF9XBTN
 *   │  │ └── 10 位随机（32 字符表，2^50 种）
 *   │  └──── 域标记：M=积分商城，C=纸质证书
 *   └─────── 固定前缀，肉眼可辨认「这是取货码」
 * </pre>
 *
 * <p><b>字母表刻意去掉 0 1 I O</b>（余 {@code 23456789} + 24 个字母 = 32）：
 * 取货码会被人念出来、手抄、在窗口报给工作人员，这四个字符是手写与屏幕上最常见的混淆源。
 * 去掉之后<b>不需要任何输入纠错映射</b>——凡是出现这四个字符的输入，一定是错的，直接拒绝即可。</p>
 *
 * <p><b>域标记的作用是让跨域碰撞不可能发生</b>。两个域共用同一个码空间时，
 * 理论上可能生成同一个串（各自的唯一键只管各自的表）；将来若做一个统一的核销台，
 * 一次扫描就会命中两张单。加一个字符就从构造上排除了它。</p>
 *
 * <h2>随机源必须是 {@link SecureRandom}</h2>
 *
 * <p><b>取货码是柜台上的持有者凭据</b>：谁出示它，谁就把东西领走——核销员照着屏幕发货，
 * 不会另行核对身份。这与 {@code MallOrderService} 的订单号不同（那只是个业务编号，
 * 猜到了也没用，故用 {@code ThreadLocalRandom} 足够）。可预测的取货码 =
 * 任何人都能领走别人的东西，所以这里用 {@link SecureRandom}，且给足 2^50 的取值空间。</p>
 *
 * <p><b>唯一性由调用方的表保证，不由本类保证</b>：本类只管「随机得足够开」，
 * 撞了要靠 {@code uk_pickup_code} 报重复键、调用方换一个重试（同证书编号那条路）。</p>
 *
 * @author hengde
 */
public final class PickupCodeUtil {

    private PickupCodeUtil() {
    }

    /** 固定前缀。 */
    public static final String PREFIX = "PU";

    /** 域标记：积分商城兑换单。 */
    public static final char DOMAIN_MALL = 'M';

    /**
     * 域标记：纸质证书申请单（honor 第 4B 批，<b>当前冻结</b>）。
     *
     * <p>在这里先占住，是为了让「两个域不会挑到同一个字母」这件事有唯一出处——
     * 等 4B 解冻时它已经定好了，不必再回头协调。</p>
     */
    public static final char DOMAIN_CERTIFICATE = 'C';

    /** 字母表：去掉 0 1 I O 的 32 个字符。 */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 随机段长度。32^10 ≈ 1.1e15，约 2^50。 */
    private static final int RANDOM_LENGTH = 10;

    /** 整码格式：前缀 + 域标记 + 随机段，全部取自字母表。 */
    private static final Pattern PATTERN = Pattern.compile("^PU[2-9A-HJ-NP-Z]{" + (RANDOM_LENGTH + 1) + "}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成一个取货码。
     *
     * @param domain 域标记，取 {@link #DOMAIN_MALL} / {@link #DOMAIN_CERTIFICATE}
     * @return 形如 {@code PUM7K3QF9XBTN} 的 13 位码
     */
    public static String generate(char domain) {
        if (ALPHABET.indexOf(domain) < 0) {
            throw new BusinessException("非法的取货码域标记");
        }
        StringBuilder sb = new StringBuilder(PREFIX.length() + 1 + RANDOM_LENGTH);
        sb.append(PREFIX).append(domain);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 把用户输入 / 扫码结果规整成标准形态：去掉空白与分隔符、转大写。
     *
     * <p>扫码枪可能带回车、人可能抄成 {@code pu m-7k3q...}。<b>但只做这些无损整理，
     * 不做字符替换</b>（不把 {@code O} 当 {@code 0} 之类）——字母表已经排除了那四个混淆字符，
     * 凡是出现它们的输入本就是错的，替换只会把「抄错了」伪装成「找不到这张单」。</p>
     *
     * @param raw 原始输入，可为 null
     * @return 规整后的串；raw 为 null 时返回 null（<b>不代表合法</b>，合法性问 {@link #isValid}）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || c == '_' || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /** 格式是否合法（不校验域标记，也不校验它是否真的存在于某张表）。 */
    public static boolean isValid(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }

    /**
     * 格式合法<b>且</b>属于指定域。
     *
     * <p>核销入口应当用这一个：拿着证书取货码到商城柜台，应当当场报「不是本处的取货码」，
     * 而不是去 {@code mall_order} 里查一趟再报「查无此单」。</p>
     */
    public static boolean isValid(String code, char domain) {
        return isValid(code) && code.charAt(PREFIX.length()) == domain;
    }

    /**
     * 渲染成 Code128 条码 PNG data URL，供小程序 {@code <image>} 直接展示、柜台扫描。
     *
     * @param code 取货码（须先通过 {@link #isValid}）
     * @return {@code data:image/png;base64,...}
     */
    public static String toBarcodeDataUrl(String code) {
        if (!isValid(code)) {
            throw new BusinessException("取货码格式不正确");
        }
        return BarcodeUtil.toCode128PngDataUrl(code, 480, 120);
    }
}
