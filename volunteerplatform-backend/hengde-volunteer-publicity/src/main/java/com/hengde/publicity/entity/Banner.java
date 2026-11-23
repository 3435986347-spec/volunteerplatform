package com.hengde.publicity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("publicity_banner")
public class Banner extends BaseEntity {
    private String title;
    private String imageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer sort;
    private Integer status;
}
