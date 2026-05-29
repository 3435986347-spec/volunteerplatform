package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.dto.CheckInDTO;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.ServiceRecordVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-自助签到 + 我的服务记录。登录态由 {@code /v/**} 路由统一校验，loginId 即 volunteer.id。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-签到/服务记录")
@RestController
@RequestMapping("/v/activity")
public class AttendanceController {

    private AttendanceService attendanceService;
    private ServiceRecordService serviceRecordService;

    @Autowired
    public void setAttendanceService(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Autowired
    public void setServiceRecordService(ServiceRecordService serviceRecordService) {
        this.serviceRecordService = serviceRecordService;
    }

    @Operation(summary = "自助签到（GPS 距活动 ≤ 半径 + 时间窗口）")
    @PostMapping("/activities/{id}/check-in")
    public Result<Void> checkIn(@PathVariable Long id, @RequestBody @Valid CheckInDTO dto) {
        attendanceService.checkIn(id, StpUtil.getLoginIdAsLong(), dto.getLat(), dto.getLng(), dto.getMethod());
        return Result.ok();
    }

    @Operation(summary = "我的服务记录（活动名称/签到/签退/时长）")
    @GetMapping("/service-records")
    public Result<PageResult<ServiceRecordVO>> myServiceRecords(PageQuery query) {
        return Result.ok(serviceRecordService.myRecords(query, StpUtil.getLoginIdAsLong()));
    }
}
