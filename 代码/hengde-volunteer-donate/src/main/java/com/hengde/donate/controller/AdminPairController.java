package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.PairVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-结对与众筹项目（{@code /a/donate}，V3 结对批）。全部挂 {@code donate:project}。
 *
 * <p><b>「确认结对成立」是出证的触发点</b>（《协会待确认清单-v3》⑨ 的默认口径）：确认之后 honor 会发一张
 * 盖章的捐赠证书；取消一条已成立的结对会连带撤销那张证书（软删、可恢复）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-结对与众筹")
@RestController
@RequestMapping("/a/donate")
public class AdminPairController {

    private PairProjectService projectService;
    private PairService pairService;
    private CrowdfundService crowdfundService;

    @Autowired
    public void setProjectService(PairProjectService projectService) {
        this.projectService = projectService;
    }

    @Autowired
    public void setPairService(PairService pairService) {
        this.pairService = pairService;
    }

    @Autowired
    public void setCrowdfundService(CrowdfundService crowdfundService) {
        this.crowdfundService = crowdfundService;
    }

    // ---------- 结对项目 ----------

    @Operation(summary = "结对项目列表（状态 / 类型 / 关键词）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/pair-projects")
    public Result<PageResult<PairVOs.Project>> projects(@RequestParam(required = false) Integer status,
                                                        @RequestParam(required = false) Integer projectType,
                                                        @RequestParam(required = false) String keyword,
                                                        PageQuery query) {
        return Result.ok(projectService.listForAdmin(query, status, projectType, keyword));
    }

    @Operation(summary = "新建结对项目（落草稿）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pair-projects")
    public Result<Long> createProject(@Valid @RequestBody PairDTOs.ProjectSave dto) {
        return Result.ok(projectService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "结对项目详情")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/pair-projects/{id}")
    public Result<PairVOs.Project> projectDetail(@PathVariable Long id) {
        return Result.ok(projectService.detailForAdmin(id));
    }

    @Operation(summary = "修改结对项目（已结束的不能改）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PutMapping("/pair-projects/{id}")
    public Result<Void> updateProject(@PathVariable Long id, @Valid @RequestBody PairDTOs.ProjectSave dto) {
        projectService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除结对项目（仅草稿且无人登记）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @DeleteMapping("/pair-projects/{id}")
    public Result<Void> deleteProject(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "上架（草稿 → 进行中，志愿者端从此可见）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pair-projects/{id}/publish")
    public Result<Void> publishProject(@PathVariable Long id) {
        projectService.publish(id);
        return Result.ok();
    }

    @Operation(summary = "结束项目（不再接受新的结对登记）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pair-projects/{id}/end")
    public Result<Void> endProject(@PathVariable Long id) {
        projectService.end(id);
        return Result.ok();
    }

    // ---------- 结对登记 ----------

    @Operation(summary = "结对登记列表（按项目 / 状态；含结对人姓名与电话）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/pairs")
    public Result<PageResult<PairVOs.PairRecord>> pairs(@RequestParam(required = false) Long projectId,
                                                        @RequestParam(required = false) Integer status,
                                                        PageQuery query) {
        return Result.ok(pairService.listForAdmin(query, projectId, status));
    }

    @Operation(summary = "确认结对成立（认捐额累加；**出证的触发点**）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pairs/{id}/establish")
    public Result<Void> establish(@PathVariable Long id) {
        pairService.establish(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "取消结对登记（原因必填；已成立的会退回认捐额并撤销证书）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pairs/{id}/cancel")
    public Result<Void> cancelPair(@PathVariable Long id, @Valid @RequestBody PairDTOs.Cancel dto) {
        pairService.cancel(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    // ---------- 受助方来信 ----------

    @Operation(summary = "来信列表（后台看全部，含写给某个结对人的）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/pair-projects/{id}/letters")
    public Result<PageResult<PairVOs.Letter>> letters(@PathVariable Long id, PageQuery query) {
        return Result.ok(projectService.lettersForAdmin(id, query));
    }

    @Operation(summary = "录入受助方来信（图文；不填收信人=项目公开信）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/pair-projects/{id}/letters")
    public Result<Long> addLetter(@PathVariable Long id, @Valid @RequestBody PairDTOs.LetterSave dto) {
        return Result.ok(projectService.addLetter(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "删除来信")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @DeleteMapping("/letters/{id}")
    public Result<Void> deleteLetter(@PathVariable Long id) {
        projectService.deleteLetter(id);
        return Result.ok();
    }

    // ---------- 众筹项目 ----------

    @Operation(summary = "众筹项目列表")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/crowdfunds")
    public Result<PageResult<PairVOs.Crowdfund>> crowdfunds(@RequestParam(required = false) Integer status,
                                                            @RequestParam(required = false) String keyword,
                                                            PageQuery query) {
        return Result.ok(crowdfundService.listForAdmin(query, status, keyword));
    }

    @Operation(summary = "新建众筹项目（落草稿）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/crowdfunds")
    public Result<Long> createCrowdfund(@Valid @RequestBody PairDTOs.CrowdfundSave dto) {
        return Result.ok(crowdfundService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "众筹项目详情")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/crowdfunds/{id}")
    public Result<PairVOs.Crowdfund> crowdfundDetail(@PathVariable Long id) {
        return Result.ok(crowdfundService.detailForAdmin(id));
    }

    @Operation(summary = "修改众筹项目（已结束的不能改）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PutMapping("/crowdfunds/{id}")
    public Result<Void> updateCrowdfund(@PathVariable Long id, @Valid @RequestBody PairDTOs.CrowdfundSave dto) {
        crowdfundService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除众筹项目（仅草稿）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @DeleteMapping("/crowdfunds/{id}")
    public Result<Void> deleteCrowdfund(@PathVariable Long id) {
        crowdfundService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "上架众筹项目")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/crowdfunds/{id}/publish")
    public Result<Void> publishCrowdfund(@PathVariable Long id) {
        crowdfundService.publish(id);
        return Result.ok();
    }

    @Operation(summary = "结束众筹项目")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/crowdfunds/{id}/end")
    public Result<Void> endCrowdfund(@PathVariable Long id) {
        crowdfundService.end(id);
        return Result.ok();
    }
}
