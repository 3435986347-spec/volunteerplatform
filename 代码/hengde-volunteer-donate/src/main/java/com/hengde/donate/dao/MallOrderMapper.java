package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallOrder;

/**
 * MallOrder Mapper。
 *
 * <p>状态迁移一律走带 CAS 条件的 {@code LambdaUpdateWrapper}（在 service 里），
 * 靠影响行数判定，不在这里另开手写语句。</p>
 *
 * @author hengde
 */
public interface MallOrderMapper extends BaseMapper<MallOrder> {
}
