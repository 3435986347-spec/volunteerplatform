package com.hengde.donate;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.service.MallGoodsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 商品审核态的<b>隔离级测试</b>：只在方法开头 {@code if} 一下挡不住「修改先完成、审核后完成」。
 *
 * <p><b>为什么必须这样写</b>：顺序调用的用例（先提交审核、再改内容）<b>根本没有窗口，永远绿</b>。
 * 缺陷只在「本事务已建立快照 → 别人提交了状态变更 → 本事务据旧快照做决定」这个交错里现形。
 * 故在一个显式 {@code ISOLATION_REPEATABLE_READ} 的事务里先读一次建立快照 →
 * 让另一个连接改状态并提交 → 断言快照仍读到旧值（<b>用例有效性自检</b>）→ 再调被测方法。</p>
 *
 * <p><b>自检用 {@link JdbcTemplate} 而不是 Mapper</b>：MyBatis 一级缓存是 SESSION 级的，
 * 同一事务里第二次读同一行会直接返回上次的对象、<b>根本不发 SQL</b>，
 * 那样自检就成了「缓存还在不在」而不是「快照隔离成不成立」。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallGoodsReviewIsolationTest {

    private static final long GOODS_ID = 991_001L;

    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM mall_goods WHERE id = ?", GOODS_ID);
        jdbcTemplate.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, "
                        + "is_deleted) VALUES (?, '隔离用例商品', ?, 0, 0, NOW(), NOW(), 0)",
                GOODS_ID, MallGoodsStatus.ON_SALE);
    }

    /**
     * 核心：本事务快照里它还是「已上架」，实际已被别人改成「待审核」——此时 update 必须被拒。
     *
     * <p>方法开头那个 {@code if} 读的是快照，会认为「不是待审核，可以改」；
     * 挡住它的是 UPDATE 语句 WHERE 里的 {@code status <> 待审核} 与影响行数判定。
     * <b>去掉那个条件，本用例必红</b>——它就是那条 CAS 的承重测试。</p>
     */
    @Test
    void updateIsRejectedWhenGoodsBecamePendingAfterSnapshot() {
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionTemplate.getTransactionManager());
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        repeatableRead.execute(status -> {
            // ① 先读一次，建立本事务的读视图
            Integer snapshot = jdbcTemplate.queryForObject(
                    "SELECT status FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID);
            assertEquals(MallGoodsStatus.ON_SALE, snapshot, "前置：本事务快照里应是已上架");

            // ② 另一个连接把它改成待审核并提交（NEW 事务，独立提交）
            commitInSeparateTransaction();

            // ③ 用例有效性自检：对方已提交，本事务快照仍应读到旧值
            Integer stillOld = jdbcTemplate.queryForObject(
                    "SELECT status FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID);
            assertEquals(MallGoodsStatus.ON_SALE, stillOld,
                    "自检失败：快照已能看到对方的提交，本用例就没有覆盖到那个交错");

            // ④ 被测方法：快照说「可以改」，而库里此刻是待审核 → 必须被拒
            MallGoods values = new MallGoods();
            values.setName("改成别的名字");
            BusinessException e = assertThrows(BusinessException.class,
                    () -> goodsService.update(GOODS_ID, values),
                    "审核中的商品不得被修改——挡住它的是 UPDATE 的 WHERE，不是方法开头的 if");

            // 被测方法自己带 @Transactional，抛异常时会把这个共享事务标成 rollback-only；
            // 那是正确行为，所以本事务只能回滚、不能提交。这里显式标记，让 TransactionTemplate
            // 安静地回滚，而不是在提交阶段抛 UnexpectedRollbackException 把用例搅成 Error。
            // 本事务里只做了读，回滚不影响下面第 ⑤ 步的断言。
            status.setRollbackOnly();
            return e;
        });

        // ⑤ 内容确实没被改掉
        String name = jdbcTemplate.queryForObject(
                "SELECT name FROM mall_goods WHERE id = ?", String.class, GOODS_ID);
        assertEquals("隔离用例商品", name, "被拒之后不得留下部分修改");
    }

    /**
     * 已上架的商品被修改 → 退回待审核，且上一次的审核痕迹被清掉。
     *
     * <p>不清痕迹的话，驳回原因会一直挂在那儿误导下一位审核人；
     * 而 {@code updateById} 会跳过 null 导致「清不掉」，所以实现用的是显式 {@code .set(...)}。</p>
     */
    @Test
    void updatingApprovedGoodsSendsItBackToReview() {
        jdbcTemplate.update("UPDATE mall_goods SET review_by = 9, review_time = NOW(), "
                + "reject_reason = '上一次的驳回原因' WHERE id = ?", GOODS_ID);

        MallGoods values = new MallGoods();
        values.setName("改过的名字");
        goodsService.update(GOODS_ID, values);

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID);
        assertEquals(MallGoodsStatus.PENDING, status, "改已上架的商品必须退回待审核");
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mall_goods WHERE id = ? AND (review_by IS NOT NULL "
                                + "OR reject_reason IS NOT NULL)", Integer.class, GOODS_ID),
                "退回重审时上一次的审核痕迹必须清掉");
    }

    /**
     * 排序 / 隐藏走独立入口，<b>不触发重审</b>。
     *
     * <p>⚠️ 这条同时挡住另一种写法：若 {@code updateDisplay} 实现成「读实体 → 改字段 → updateById」，
     * 它会把整行写回去，与并发的「改内容 → 退回待审核」互相覆盖，等于绕过审核。</p>
     */
    @Test
    void changingSortOrHiddenDoesNotResetReviewStatus() {
        goodsService.updateDisplay(GOODS_ID, 7, 1);

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID);
        assertEquals(MallGoodsStatus.ON_SALE, status, "改排序/隐藏不得触发重审");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT hidden FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID));
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT sort FROM mall_goods WHERE id = ?", Integer.class, GOODS_ID));
    }

    private void commitInSeparateTransaction() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.execute(s -> jdbcTemplate.update(
                "UPDATE mall_goods SET status = ? WHERE id = ?", MallGoodsStatus.PENDING, GOODS_ID));
    }
}
