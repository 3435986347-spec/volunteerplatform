package com.hengde.activity.album.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 活动相册（V74）。 */
@Getter
@Setter
@TableName("activity_album")
public class ActivityAlbum extends BaseEntity {
    private Long activityId;
    private String title;
    private Integer createdByType;
    private Long createdBy;
    private Long deletedBy;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeActivityKey;
}
