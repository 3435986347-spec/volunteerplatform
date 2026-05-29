package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class StructureNodeVO {
    private Long id;
    private Long parentId;
    private String name;
    private String title;
    private Integer sort;
    private List<StructureNodeVO> children = new ArrayList<>();
}
