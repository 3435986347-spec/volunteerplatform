package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.service.MyActivityService;
import com.hengde.activity.vo.MyActivityDetailVO;
import com.hengde.activity.vo.MyActivityVO;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-我的活动（参与者视角）。登录态由 {@code /v/**} 路由统一校验，loginId 即 volunteer.id。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-我的活动")
@RestController
@RequestMapping("/v/activity/my-activities")
public class MyActivityController {

    private MyActivityService myActivityService;

    @Autowired
    public void setMyActivityService(MyActivityService myActivityService) {
        this.myActivityService = myActivityService;
    }

    @Operation(summary = "我的活动列表（名称/时间段/负责人/签到/是否违规/考勤）")
    @GetMapping
    public Result<List<MyActivityVO>> myActivities() {
        return Result.ok(myActivityService.myActivities(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我的活动详情（含考勤 + 签到二维码数据 + 确认到家 + 评价回显）")
    @GetMapping("/{id}")
    public Result<MyActivityDetailVO> detail(@PathVariable Long id) {
        return Result.ok(myActivityService.myActivityDetail(StpUtil.getLoginIdAsLong(), id));
    }
}
