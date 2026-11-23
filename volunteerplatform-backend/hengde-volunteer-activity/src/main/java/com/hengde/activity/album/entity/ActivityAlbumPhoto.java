package com.hengde.activity.album.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 相册照片（V74）。 */
@Getter
@Setter
@TableName("activity_album_photo")
public class ActivityAlbumPhoto extends BaseEntity {
    private Long albumId;
    private Long batchId;
    private Integer uploaderType;
    private Long uploaderId;
    private String url;
    private Long deletedBy;
}
