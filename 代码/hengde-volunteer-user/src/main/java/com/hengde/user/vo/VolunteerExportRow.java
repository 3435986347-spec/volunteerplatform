package com.hengde.user.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Getter;
import lombok.Setter;

/**
 * 志愿者名单导出行（{@code GET /a/user/volunteers/export}）。列由 {@link ExcelProperty} 注解决定，
 * 由 {@link com.hengde.common.excel.ExcelUtil#export} 写出。值用展示态（label/明文/小时数）。
 *
 * @author hengde
 */
@Getter
@Setter
@ColumnWidth(16)
public class VolunteerExportRow {

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("手机号")
    private String phone;

    @ExcelProperty("学校")
    private String school;

    @ExcelProperty("年级")
    private String grade;

    @ExcelProperty("政治面貌")
    private String political;

    @ExcelProperty("归属分队")
    private String squad;

    @ExcelProperty("所在小组")
    private String group;

    @ExcelProperty("管理团队")
    private String managerFlag;

    @ExcelProperty("服务时长(h)")
    private Double hours;

    @ExcelProperty("累计积分")
    private Integer points;

    @ExcelProperty("参与活动(次)")
    private Integer activities;

    @ExcelProperty("账号状态")
    private String status;
}
