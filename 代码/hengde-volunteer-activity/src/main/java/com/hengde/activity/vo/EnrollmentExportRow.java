package com.hengde.activity.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 报名名单导出行（EasyExcel）。列由 {@link ExcelProperty} 注解顺序决定；
 * 年级/性别/状态导出为可读文案，时间导出为字符串。
 *
 * @author hengde
 */
@Data
public class EnrollmentExportRow {

    @ExcelProperty(value = "姓名", index = 0)
    private String realName;

    @ExcelProperty(value = "手机号", index = 1)
    private String phone;

    @ExcelProperty(value = "学校", index = 2)
    private String school;

    @ExcelProperty(value = "年级", index = 3)
    private String grade;

    @ExcelProperty(value = "性别", index = 4)
    private String gender;

    @ExcelProperty(value = "项目", index = 5)
    private String projectName;

    @ExcelProperty(value = "时间段开始", index = 6)
    private String slotStartTime;

    @ExcelProperty(value = "时间段结束", index = 7)
    private String slotEndTime;

    @ExcelProperty(value = "报名状态", index = 8)
    private String status;

    @ExcelProperty(value = "报名时间", index = 9)
    private String enrollTime;
}
