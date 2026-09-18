package com.hengde.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 个人中心内容（V66）：「我的保险」「联系客服」这类后台设置、所有人看同一份的文字 + 图片。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_center_content")
public class UserCenterContent extends BaseEntity {

    private String contentKey;
    private String title;
    private String body;
    /** 逗号分隔 */
    private String images;
    private Long updateBy;
}
