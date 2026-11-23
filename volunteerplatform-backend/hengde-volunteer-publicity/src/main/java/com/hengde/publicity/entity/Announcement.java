package com.hengde.publicity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("publicity_announcement")
public class Announcement extends BaseEntity {
    private String title;
    private String summary;
    private String content;
    private String coverImageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer status;
    private LocalDateTime publishTime;
}
