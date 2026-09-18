package com.hengde.social.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.social.entity.SocialUserSetting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 社区主页设置。
 *
 * @author hengde
 */
public interface SocialUserSettingMapper extends BaseMapper<SocialUserSetting> {

    /** 整行写入（没有就插，有就覆盖）——设置页一次提交全部开关，没有「先查再插」的并发窗口。 */
    @Insert("INSERT INTO social_user_setting (volunteer_id, forbid_follow, forbid_comment, forbid_like, forbid_chat, bio, update_time) "
            + "VALUES (#{s.volunteerId}, #{s.forbidFollow}, #{s.forbidComment}, #{s.forbidLike}, #{s.forbidChat}, #{s.bio}, NOW()) "
            + "ON DUPLICATE KEY UPDATE forbid_follow = VALUES(forbid_follow), forbid_comment = VALUES(forbid_comment), "
            + "forbid_like = VALUES(forbid_like), forbid_chat = VALUES(forbid_chat), bio = VALUES(bio), update_time = NOW()")
    int upsert(@Param("s") SocialUserSetting setting);
}
