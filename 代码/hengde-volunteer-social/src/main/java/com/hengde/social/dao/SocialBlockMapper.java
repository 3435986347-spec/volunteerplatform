package com.hengde.social.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 不让TA看（物理行；唯一键 {@code uk_owner_target}）。
 *
 * @author hengde
 */
@Mapper
public interface SocialBlockMapper {

    @Insert("INSERT INTO social_block (owner_id, target_id, create_time) VALUES (#{ownerId}, #{targetId}, NOW())")
    int insert(@Param("ownerId") Long ownerId, @Param("targetId") Long targetId);

    @Delete("DELETE FROM social_block WHERE owner_id = #{ownerId} AND target_id = #{targetId}")
    int delete(@Param("ownerId") Long ownerId, @Param("targetId") Long targetId);

    @Select("SELECT COUNT(*) FROM social_block WHERE owner_id = #{ownerId} AND target_id = #{targetId}")
    long exists(@Param("ownerId") Long ownerId, @Param("targetId") Long targetId);

    @Select("SELECT target_id FROM social_block WHERE owner_id = #{ownerId} ORDER BY create_time DESC, id DESC")
    List<Long> selectTargets(@Param("ownerId") Long ownerId);
}
