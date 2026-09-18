package com.hengde.user.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.user.entity.UserAddress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址。
 *
 * @author hengde
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {
}
