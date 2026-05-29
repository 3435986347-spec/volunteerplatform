package com.hengde.publicity.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AnnouncementVO {
    private Long id;
    private String title;
    private String summary;
    private String content;
    private String coverImageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer status;
    private LocalDateTime publishTime;
}
