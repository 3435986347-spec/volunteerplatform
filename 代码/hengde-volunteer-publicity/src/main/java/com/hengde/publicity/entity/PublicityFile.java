package com.hengde.publicity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("publicity_file")
public class PublicityFile extends BaseEntity {
    private String fileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer downloadable;
    private Integer sort;
}
