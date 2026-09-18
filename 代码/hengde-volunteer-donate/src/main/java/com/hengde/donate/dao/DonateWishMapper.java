package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateWish;

/**
 * DonateWish Mapper。状态迁移一律走带 CAS 条件的 {@code LambdaUpdateWrapper}（在 service 里）。
 *
 * @author hengde
 */
public interface DonateWishMapper extends BaseMapper<DonateWish> {
}
