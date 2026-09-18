package com.hengde.activity.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 相册上传批次（V74）。 */
@Getter
@Setter
@TableName("activity_album_batch")
public class ActivityAlbumBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long albumId;
    private Integer uploaderType;
    private Long uploaderId;
    private Integer photoCount;
    private String comment;
    private Integer syncSocial;
    private Long socialPostId;
    private Integer pointsStatus;
    private Integer awardedPoints;
    private String rejectReason;
    private Long reviewedBy;
    private LocalDateTime reviewedTime;
    private LocalDateTime createTime;
}
