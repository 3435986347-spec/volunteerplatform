package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.Volunteer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 志愿者 Mapper。继承 {@link BaseMapper} 即得基础 CRUD；
 * 按 openid / id_card_hash / phone_hash 的查询用条件构造器在 service 里组织。
 *
 * @author hengde
 */
@Mapper
public interface VolunteerMapper extends BaseMapper<Volunteer> {
}
