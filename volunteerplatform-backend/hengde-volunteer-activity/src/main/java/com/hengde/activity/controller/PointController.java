package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.service.PointService;
import com.hengde.activity.vo.PointRecordVO;
import com.hengde.activity.vo.PointSummaryVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 志愿者端-积分中心。登录态由 {@code /v/**} 路由统一校验，loginId 即 volunteer.id。
 *
 * <p>只读本人积分，志愿者 id 一律取自登录态、不接受入参，避免越权查他人积分。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-积分中心")
@RestController
@RequestMapping("/v/activity")
public class PointController {

    private PointService pointService;

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Operation(summary = "我的积分总览（总积分/已使用积分/当前余额）")
    @GetMapping("/points")
    public Result<PointSummaryVO> summary() {
        return Result.ok(pointService.summary(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我的积分明细（分页，可搜索说明并按来源类型/时间区间筛选）")
    @GetMapping("/points/records")
    public Result<PageResult<PointRecordVO>> records(
            PageQuery query,
            @RequestParam(required = false) Integer sourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String keyword) {
        return Result.ok(pointService.pageRecords(StpUtil.getLoginIdAsLong(), query,
                sourceType, startTime, endTime, keyword));
    }
}
