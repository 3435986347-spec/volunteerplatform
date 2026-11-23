package com.hengde.activity.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 相册上传积分规则（单行，V74）。 */
@Getter
@Setter
@TableName("activity_album_rule")
public class ActivityAlbumRule {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer enabled;
    private Integer photosPerUnit;
    private Integer pointsPerUnit;
    private Integer maxPointsPerAlbum;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
