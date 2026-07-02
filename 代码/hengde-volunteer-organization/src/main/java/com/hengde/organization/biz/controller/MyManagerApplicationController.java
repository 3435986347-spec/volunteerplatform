package com.hengde.organization.biz.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.service.ManagerApplicationService;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-报名管理团队（问卷+审核）。提交申请 + 查看本人申请状态。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-报名管理团队")
@RestController
@RequestMapping("/v/organization/manager-applications")
public class MyManagerApplicationController {

    private ManagerApplicationService managerApplicationService;

    @Autowired
    public void setManagerApplicationService(ManagerApplicationService managerApplicationService) {
        this.managerApplicationService = managerApplicationService;
    }

    @Operation(summary = "提交报名管理团队申请")
    @PostMapping
    public Result<Long> apply(@RequestBody @Valid ManagerApplyDTO dto) {
        return Result.ok(managerApplicationService.apply(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "我的报名管理团队申请（最近一条，状态回显；无则 null）")
    @GetMapping("/mine")
    public Result<ManagerApplicationVO> mine() {
        return Result.ok(managerApplicationService.myApplication(StpUtil.getLoginIdAsLong()));
    }
}
