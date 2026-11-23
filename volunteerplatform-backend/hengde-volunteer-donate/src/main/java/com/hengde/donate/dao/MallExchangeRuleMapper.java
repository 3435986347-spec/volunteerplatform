package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallExchangeRule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MallExchangeRule Mapper。
 *
 * @author hengde
 */
public interface MallExchangeRuleMapper extends BaseMapper<MallExchangeRule> {

    /**
     * 写入唯一那一行。<b>一条语句完成「有则改、无则建」</b>，影响行数 1（插入）或 2（更新）。
     *
     * <p><b>为什么不是「先查再决定 insert 还是 update」</b>：那是本项目反复清理过的形状——
     * 查与写之间隔着别人的一次提交，并发下两个人同时保存会一个插入、一个撞主键。
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 把它压成一条原子语句。</p>
     *
     * <p>迁移里已经把 id=1 建出来了，所以正常情况下永远走 UPDATE 分支；
     * INSERT 分支是兜底——这一行被人手工删掉时能自愈，而不是让保存按钮从此静默失败。</p>
     *
     * <p><b>{@code is_deleted} 显式写 0</b>：手写语句不吃 {@code @TableLogic}
     * （同 {@code MallGoodsSpecMapper.deductStock} 那一课）。若这一行曾被软删，
     * 这里要把它救回来——本表根本没有删除入口，留着软删态只会让页面永远空白。</p>
     *
     * @param content  正文
     * @param images   配图 URL，换行分隔
     * @param updateBy 修改人
     * @return 影响行数（1=插入，2=更新）
     */
    @Update("INSERT INTO mall_exchange_rule (id, content, images, update_by, create_time, update_time, is_deleted) "
            + "VALUES (1, #{content}, #{images}, #{updateBy}, NOW(), NOW(), 0) "
            + "ON DUPLICATE KEY UPDATE content = VALUES(content), images = VALUES(images), "
            + "update_by = VALUES(update_by), update_time = NOW(), is_deleted = 0")
    int save(@Param("content") String content,
             @Param("images") String images,
             @Param("updateBy") Long updateBy);
}
