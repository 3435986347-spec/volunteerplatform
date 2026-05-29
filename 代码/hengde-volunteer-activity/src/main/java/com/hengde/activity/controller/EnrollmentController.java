package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.dto.EnrollDTO;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.vo.MyEnrollmentVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-活动报名/取消/我的报名。登录态由 {@code /v/**} 路由统一校验，loginId 即 volunteer.id。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-报名")
@RestController
@RequestMapping("/v/activity")
public class EnrollmentController {

    private EnrollmentService enrollmentService;

    @Autowired
    public void setEnrollmentService(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @Operation(summary = "报名（body 指定时间段）")
    @PostMapping("/activities/{id}/enroll")
    public Result<Integer> enroll(@PathVariable Long id, @RequestBody @Valid EnrollDTO dto) {
        return Result.ok(enrollmentService.enroll(id, dto.getSlotIds(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "取消报名（整活动取消）")
    @DeleteMapping("/activities/{id}/enroll")
    public Result<Integer> cancel(@PathVariable Long id) {
        return Result.ok(enrollmentService.cancel(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "代报名：同小组成员之间互相帮报名")
    @PostMapping("/activities/{id}/proxy-enrollments")
    public Result<Integer> proxyEnroll(@PathVariable Long id, @RequestBody @Valid ProxyEnrollDTO dto) {
        return Result.ok(enrollmentService.proxyEnroll(id, dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我的报名列表（可按状态筛选）")
    @GetMapping("/my-enrollments")
    public Result<PageResult<MyEnrollmentVO>> myEnrollments(PageQuery query,
                                                            @RequestParam(required = false) Integer status) {
        return Result.ok(enrollmentService.myEnrollments(query, StpUtil.getLoginIdAsLong(), status));
    }
}
