package com.hengde.donate;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.dto.ExchangeRuleSaveDTO;
import com.hengde.donate.service.MallExchangeRuleService;
import com.hengde.donate.vo.ExchangeRuleVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兑换规则（Row 8 C）——单行表、覆盖式、无版本。
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallExchangeRuleServiceTest {

    private static final long ADMIN_ID = 995_800L;

    @Autowired
    private MallExchangeRuleService ruleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("UPDATE mall_exchange_rule SET content = NULL, images = NULL, "
                + "update_by = NULL, is_deleted = 0 WHERE id = 1");
    }

    /** 迁移已把 id=1 建出来，所以读路径永远查得到——协会没填时是空内容，不是错误。 */
    @Test
    void emptyRuleReadsAsBlankNotAnError() {
        ExchangeRuleVO vo = ruleService.get();

        assertNull(vo.getContent());
        assertNotNull(vo.getImages(), "未填写时给空列表，不给 null，免得前端各自判空");
        assertTrue(vo.getImages().isEmpty());
    }

    @Test
    void saveThenReadRoundTrips() {
        ruleService.save(dto("每满 100 积分可兑换一次", List.of("https://x/a.png", "https://x/b.png")), ADMIN_ID);

        ExchangeRuleVO vo = ruleService.get();
        assertEquals("每满 100 积分可兑换一次", vo.getContent());
        assertEquals(List.of("https://x/a.png", "https://x/b.png"), vo.getImages());
        assertNotNull(vo.getUpdateTime());
        assertEquals(ADMIN_ID, jdbcTemplate.queryForObject(
                "SELECT update_by FROM mall_exchange_rule WHERE id = 1", Long.class), "落审计");
    }

    /** 覆盖式、无版本：第二次保存就是当前值，库里不留第二行。 */
    @Test
    void saveOverwritesAndNeverAddsRows() {
        ruleService.save(dto("第一版", List.of("https://x/a.png")), ADMIN_ID);
        ruleService.save(dto("第二版", List.of()), ADMIN_ID);

        ExchangeRuleVO vo = ruleService.get();
        assertEquals("第二版", vo.getContent());
        assertTrue(vo.getImages().isEmpty(), "传空列表 = 清空配图");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_exchange_rule", Integer.class), "单行表，永远只有一行");
    }

    /**
     * URL 里塞换行必须被拒——分隔符方案的代价就是这一道校验。
     *
     * <p>放过去的话，一条 URL 会被切成两条指向别处的碎片，或者凭空多出一张图。
     * 之所以仍选换行分隔：URL 可以合法地含逗号，<b>不可能</b>含换行，
     * 而项目里没有任何模块引了 JSON 库（fastjson2 只在父 POM 声明过，全项目零引用）。</p>
     */
    @Test
    void urlContainingNewlineIsRejected() {
        assertThrows(BusinessException.class,
                () -> ruleService.save(dto("正文", List.of("https://x/a.png\nhttps://evil/b.png")), ADMIN_ID));
        assertThrows(BusinessException.class,
                () -> ruleService.save(dto("正文", List.of("https://x/a.png\r\nhttps://evil/b.png")), ADMIN_ID));

        assertNull(ruleService.get().getContent(), "被拒的保存不得留下部分写入");
    }

    /** 逗号在 URL 里是合法的，不能被当成分隔符切开。 */
    @Test
    void commaInUrlSurvives() {
        String url = "https://oss/x/a,b(1).png";
        ruleService.save(dto("正文", List.of(url)), ADMIN_ID);

        assertEquals(List.of(url), ruleService.get().getImages(),
                "URL 里的逗号不是分隔符——这正是没沿用 service_guarantees 那套逗号分隔的原因");
    }

    @Test
    void blankUrlsAreDropped() {
        ruleService.save(dto("正文", Arrays.asList("https://x/a.png", "  ", null)), ADMIN_ID);
        assertEquals(List.of("https://x/a.png"), ruleService.get().getImages());
    }

    @Test
    void saveRequiresOperator() {
        assertThrows(BusinessException.class, () -> ruleService.save(dto("正文", List.of()), null));
    }

    /**
     * 那一行被人手工删掉时能自愈——保存走的是 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
     *
     * <p>不这么做的话，保存按钮会从此静默失败（UPDATE 影响 0 行），而页面看着一切正常。
     * 顺带钉住软删：本表没有删除入口，留着软删态只会让页面永远空白，所以那条语句显式写回
     * {@code is_deleted = 0}（手写 SQL 不吃 {@code @TableLogic}）。</p>
     */
    @Test
    void saveHealsAMissingOrSoftDeletedRow() {
        jdbcTemplate.update("DELETE FROM mall_exchange_rule WHERE id = 1");
        ruleService.save(dto("删掉之后重新填", List.of()), ADMIN_ID);
        assertEquals("删掉之后重新填", ruleService.get().getContent());

        jdbcTemplate.update("UPDATE mall_exchange_rule SET is_deleted = 1 WHERE id = 1");
        assertNull(ruleService.get().getContent(), "软删态下读不出来（@TableLogic 生效）");

        ruleService.save(dto("救回来", List.of()), ADMIN_ID);
        assertEquals("救回来", ruleService.get().getContent(), "保存必须把软删的那一行救回来");
    }

    private ExchangeRuleSaveDTO dto(String content, List<String> images) {
        ExchangeRuleSaveDTO dto = new ExchangeRuleSaveDTO();
        dto.setContent(content);
        dto.setImages(images);
        return dto;
    }
}
