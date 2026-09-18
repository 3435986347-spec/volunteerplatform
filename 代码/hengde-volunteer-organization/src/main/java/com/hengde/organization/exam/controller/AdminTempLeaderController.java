package com.hengde.organization.exam.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.service.TempLeaderService;
import com.hengde.organization.exam.vo.ExamVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-活动临时负责人（{@code /a/organization/temp-leaders}，V4 临时负责人考试批，{@code org:temp-leader}）：名单 / 撤销资格 / 批量导出。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动临时负责人")
@RestController
@RequestMapping("/a/organization/temp-leaders")
public class AdminTempLeaderController {

    private TempLeaderService tempLeaderService;

    @Autowired
    public void setTempLeaderService(TempLeaderService tempLeaderService) {
        this.tempLeaderService = tempLeaderService;
    }

    @Operation(summary = "资格名单（含手机号；状态按时间现算）")
    @SaCheckPermission(value = PermissionCode.ORG_TEMP_LEADER, type = "admin")
    @GetMapping
    public Result<PageResult<ExamVOs.Qualification>> list(PageQuery query,
            @Parameter(description = "1有效/2已到期/3已撤销") @RequestParam(required = false) Integer status,
            @Parameter(description = "姓名片段或完整手机号") @RequestParam(required = false) String keyword) {
        return Result.ok(tempLeaderService.list(query, status, keyword));
    }

    @Operation(summary = "批量导出（xlsx，最多 20000 条）")
    @SaCheckPermission(value = PermissionCode.ORG_TEMP_LEADER, type = "admin")
    @GetMapping("/export")
    public void export(HttpServletResponse response,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        List<List<String>> rows = tempLeaderService.exportRows(status, keyword);
        ExcelUtil.exportTable(response, "活动临时负责人名单", "临时负责人", TempLeaderService.exportHead(), rows);
    }

    @Operation(summary = "撤销资格（评价过低等，须填原因）")
    @SaCheckPermission(value = PermissionCode.ORG_TEMP_LEADER, type = "admin")
    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id, @Valid @RequestBody ExamDTOs.Revoke dto) {
        tempLeaderService.revoke(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
