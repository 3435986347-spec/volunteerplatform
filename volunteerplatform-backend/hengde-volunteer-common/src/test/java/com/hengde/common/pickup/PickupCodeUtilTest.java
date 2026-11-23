package com.hengde.common.pickup;

import com.hengde.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取货码形态。纯静态工具，无需 Spring/Docker（同 {@code QrCodeUtilTest}）。
 *
 * <p><b>这些用例锁的是三条不能退让的性质</b>，不要因为「看起来只是个随机串」就删：</p>
 * <ol>
 *   <li><b>字母表不含 0 1 I O</b>——取货码要被人念、被人手抄，这四个是最常见的混淆源；
 *       排除之后输入侧不需要任何纠错映射；</li>
 *   <li><b>随机段来自 {@link java.security.SecureRandom}</b>——取货码是柜台上的持有者凭据，
 *       可预测 = 任何人都能领走别人的东西。本类无法直接断言随机源，
 *       用「大批量无重复 + 分布不塌」间接守着；</li>
 *   <li><b>域标记把跨域碰撞排除在构造之外</b>——商城与纸质证书共用码空间，各自的唯一键
 *       只管各自的表。</li>
 * </ol>
 *
 * @author hengde
 */
class PickupCodeUtilTest {

    @Test
    void generatedCodeMatchesTheDeclaredShape() {
        String code = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL);

        assertEquals(13, code.length(), "PU + 域标记 + 10 位随机");
        assertTrue(code.startsWith("PUM"), "商城域的码以 PUM 打头");
        assertTrue(PickupCodeUtil.isValid(code));
        assertTrue(PickupCodeUtil.isValid(code, PickupCodeUtil.DOMAIN_MALL));
    }

    /**
     * <b>字母表不得含 0 1 I O。</b> 抽一批码逐字符查——这是「不需要输入纠错映射」这条设计的前提，
     * 一旦有人为了「多点熵」把字母表改回全 36 个字符，本用例必红。
     */
    @Test
    void alphabetExcludesTheFourConfusableCharacters() {
        for (int i = 0; i < 2000; i++) {
            String code = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL);
            String random = code.substring(3);
            for (char c : random.toCharArray()) {
                assertFalse("01IO".indexOf(c) >= 0,
                        "取货码不得出现 0/1/I/O（手抄与念读的混淆源），实际生成：" + code);
            }
        }
    }

    /**
     * 间接守着「随机源够开」：2000 个码不得有重复，且 32 个字符应当都出现过。
     *
     * <p>把随机段换成计数器、或换成 {@code new Random(常量种子)}，这两条之一必红。</p>
     */
    @Test
    void codesAreWellSpreadAndDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        Set<Character> chars = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL);
            assertTrue(seen.add(code), "2000 个码内出现重复，随机源有问题：" + code);
            for (char c : code.substring(3).toCharArray()) {
                chars.add(c);
            }
        }
        assertEquals(32, chars.size(), "32 个字符应当都被取到过（分布塌了说明随机源不对）");
    }

    /** 域标记让「拿证书的码到商城柜台」当场被拒，而不是去表里查一趟再报查无此单。 */
    @Test
    void domainTagSeparatesTheTwoCodeSpaces() {
        String certCode = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_CERTIFICATE);

        assertTrue(PickupCodeUtil.isValid(certCode), "格式本身是合法的");
        assertFalse(PickupCodeUtil.isValid(certCode, PickupCodeUtil.DOMAIN_MALL),
                "证书域的码不得在商城域通过校验");
    }

    @Test
    void rejectsUnregisteredDomainTag() {
        // 0 不在字母表里（正是被排除的四个之一），不能当域标记
        assertThrows(BusinessException.class, () -> PickupCodeUtil.generate('0'));
        assertThrows(BusinessException.class, () -> PickupCodeUtil.generate('是'));
    }

    /**
     * {@code normalize} 只做无损整理（去分隔符与空白、转大写），<b>不做字符替换</b>。
     *
     * <p>末一条断言是重点：{@code O} 抄错了就是抄错了，把它当 {@code 0}（或反过来）
     * 只会把「抄错了」伪装成「查无此单」，而柜台前的人无从知道该改哪一位。</p>
     */
    @Test
    void normalizeTidiesInputButNeverSubstitutesCharacters() {
        String code = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL);
        String messy = " " + code.substring(0, 3).toLowerCase() + "-" + code.substring(3, 8)
                + " " + code.substring(8) + "\r\n";

        assertEquals(code, PickupCodeUtil.normalize(messy), "大小写、空白、连字符都应被整理掉");
        assertTrue(PickupCodeUtil.isValid(PickupCodeUtil.normalize(messy)));

        assertFalse(PickupCodeUtil.isValid(PickupCodeUtil.normalize("PUM0O1IABCDEF")),
                "含 0/1/I/O 的输入一定是抄错的，必须拒绝而不是替换后放行");
    }

    @Test
    void invalidShapesAreRejected() {
        assertFalse(PickupCodeUtil.isValid(null));
        assertFalse(PickupCodeUtil.isValid(""));
        assertFalse(PickupCodeUtil.isValid("PUM7K3QF9XBT"), "少一位");
        assertFalse(PickupCodeUtil.isValid("PUM7K3QF9XBTNN"), "多一位");
        assertFalse(PickupCodeUtil.isValid("XXM7K3QF9XBTN"), "前缀不对");
    }

    /** 条码渲染出 PNG data URL；非法码不给渲染（免得柜台扫出一个根本不存在的串）。 */
    @Test
    void rendersCode128DataUrl() {
        String url = PickupCodeUtil.toBarcodeDataUrl(PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL));

        assertTrue(url.startsWith("data:image/png;base64,"));
        assertTrue(url.length() > 200, "应当真的渲染出了一张图");
        assertThrows(BusinessException.class, () -> PickupCodeUtil.toBarcodeDataUrl("随便一个串"));
    }
}
