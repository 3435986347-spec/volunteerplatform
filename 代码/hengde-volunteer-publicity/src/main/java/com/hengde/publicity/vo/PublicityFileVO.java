package com.hengde.publicity.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PublicityFileVO {
    private Long id;
    private String fileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer downloadable;
    private Integer sort;
}
