package com.hengde.publicity.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BannerVO {
    private Long id;
    private String title;
    private String imageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer sort;
    private Integer status;
}
