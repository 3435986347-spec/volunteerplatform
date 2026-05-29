package com.hengde.organization.biz.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GroupImportRow {
    @ExcelProperty("小组编号")
    private String groupNo;

    @ExcelProperty("小组名称")
    private String name;

    @ExcelProperty("简介")
    private String description;

    @ExcelProperty("组长ID")
    private Long leaderId;

    @ExcelProperty("状态")
    private Integer status;
}
