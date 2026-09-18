package com.hengde.social.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.constant.PermissionCode;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.entity.SocialKeyword;
import com.hengde.social.service.SocialAdminService;
import com.hengde.social.service.SocialBanService;
import com.hengde.social.service.SocialKeywordService;
import com.hengde.social.service.SocialReportService;
import com.hengde.social.service.SocialReviewService;
import com.hengde.social.vo.SocialGovVOs;
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

import java.util.List;
import java.util.Map;

/**
 * 管理端-社区治理（V4 社区治理批，Row 23 F / Row 59）：帖子与评论管理、审核、审核设置、关键词、举报、禁言。
 *
 * @author hengde
 */
@Tag(name = "管理端-社区治理")
@RestController
@RequestMapping("/a/social")
public class AdminSocialGovController {

    private SocialAdminService adminService;
    private SocialReviewService reviewService;
    private SocialKeywordService keywordService;
    private SocialReportService reportService;
    private SocialBanService banService;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setAdminService(SocialAdminService adminService) {
        this.adminService = adminService;
    }

    @Autowired
    public void setReviewService(SocialReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Autowired
    public void setKeywordService(SocialKeywordService keywordService) {
        this.keywordService = keywordService;
    }

    @Autowired
    public void setReportService(SocialReportService reportService) {
        this.reportService = reportService;
    }

    @Autowired
    public void setBanService(SocialBanService banService) {
        this.banService = banService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    // ================= 帖子与评论 =================

    @Operation(summary = "帖子列表（按发布时间；?reviewStatus=&keywordHit=&hidden=&authorId=&keyword=；持 social:real-name 才带真实姓名与学校）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @GetMapping("/posts")
    public Result<PageResult<SocialGovVOs.AdminPost>> posts(PageQuery query, @RequestParam(required = false) Integer reviewStatus,
                                                            @RequestParam(required = false) Boolean keywordHit,
                                                            @RequestParam(required = false) Boolean hidden,
                                                            @RequestParam(required = false) Long authorId,
                                                            @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.posts(reviewStatus, keywordHit, hidden, authorId, keyword, realName(), query));
    }

    @Operation(summary = "评论列表（按时间；?postId=&authorId=&keyword=）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @GetMapping("/comments")
    public Result<PageResult<SocialGovVOs.AdminComment>> comments(PageQuery query, @RequestParam(required = false) Long postId,
                                                                  @RequestParam(required = false) Long authorId,
                                                                  @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.comments(postId, authorId, keyword, realName(), query));
    }

    @Operation(summary = "隐藏帖子（除作者外谁都看不到，可恢复）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @PostMapping("/posts/{id}/hide")
    public Result<Void> hide(@PathVariable Long id) {
        adminService.setHidden(id, true);
        return Result.ok();
    }

    @Operation(summary = "取消隐藏")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @DeleteMapping("/posts/{id}/hide")
    public Result<Void> unhide(@PathVariable Long id) {
        adminService.setHidden(id, false);
        return Result.ok();
    }

    @Operation(summary = "置顶（最新与官方页签排在最前）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @PostMapping("/posts/{id}/pin")
    public Result<Void> pin(@PathVariable Long id) {
        adminService.setPinned(id, true);
        return Result.ok();
    }

    @Operation(summary = "取消置顶")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @DeleteMapping("/posts/{id}/pin")
    public Result<Void> unpin(@PathVariable Long id) {
        adminService.setPinned(id, false);
        return Result.ok();
    }

    @Operation(summary = "删除任意帖子")
    @SaCheckPermission(value = PermissionCode.SOCIAL_POST_MANAGE, type = "admin")
    @DeleteMapping("/posts/{id}")
    public Result<Void> deletePost(@PathVariable Long id) {
        adminService.deletePost(StpAdminUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    // ================= 审核 =================

    @Operation(summary = "待我审核的帖子（命中关键词的插队在前；只列我这一级的；超管看全部级）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW, type = "admin")
    @GetMapping("/reviews")
    public Result<PageResult<SocialGovVOs.AdminPost>> reviews(PageQuery query) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        return Result.ok(reviewService.queue(adminId, adminQueryService.isSuperAdmin(adminId), realName(), query));
    }

    @Operation(summary = "审核通过这一级（同一个人不能审同一条帖子的两级）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW, type = "admin")
    @PostMapping("/reviews/{postId}/approve")
    public Result<Void> approve(@PathVariable Long postId) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        reviewService.approve(adminId, adminQueryService.isSuperAdmin(adminId), postId);
        return Result.ok();
    }

    @Operation(summary = "驳回（对他人隐藏，站内提示作者）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW, type = "admin")
    @PostMapping("/reviews/{postId}/reject")
    public Result<Void> reject(@PathVariable Long postId, @Valid @RequestBody SocialGovDTOs.Reject dto) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        reviewService.reject(adminId, adminQueryService.isSuperAdmin(adminId), postId, dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "审核设置（审核级数）")
    @SaCheckPermission(value = {PermissionCode.SOCIAL_REVIEW_SETTING, PermissionCode.SOCIAL_REVIEW}, mode = SaMode.OR, type = "admin")
    @GetMapping("/review-setting")
    public Result<Map<String, Integer>> reviewSetting() {
        return Result.ok(Map.of("levels", reviewService.levels()));
    }

    @Operation(summary = "修改审核级数（1~3；调低之后已经过够新级数的待审帖直接算通过）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @PutMapping("/review-setting")
    public Result<Void> saveReviewSetting(@Valid @RequestBody SocialGovDTOs.ReviewSetting dto) {
        reviewService.setLevels(StpAdminUtil.getLoginIdAsLong(), dto.getLevels());
        return Result.ok();
    }

    @Operation(summary = "审核员列表")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @GetMapping("/reviewers")
    public Result<List<SocialGovVOs.Reviewer>> reviewers() {
        return Result.ok(reviewService.reviewers());
    }

    @Operation(summary = "设置审核员（后台账号 + 第几级；还须另外授予 social:review）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @PostMapping("/reviewers")
    public Result<Long> addReviewer(@Valid @RequestBody SocialGovDTOs.ReviewerSave dto) {
        return Result.ok(reviewService.addReviewer(StpAdminUtil.getLoginIdAsLong(), dto.getAdminUserId(), dto.getLevel()));
    }

    @Operation(summary = "移除审核员")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @DeleteMapping("/reviewers/{id}")
    public Result<Void> removeReviewer(@PathVariable Long id) {
        reviewService.removeReviewer(id);
        return Result.ok();
    }

    // ================= 关键词 =================

    @Operation(summary = "风控关键词列表")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @GetMapping("/keywords")
    public Result<List<SocialKeyword>> keywords() {
        return Result.ok(keywordService.list());
    }

    @Operation(summary = "添加风控关键词（此后发帖 / 改帖命中即先藏后审）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @PostMapping("/keywords")
    public Result<Long> addKeyword(@Valid @RequestBody SocialGovDTOs.KeywordSave dto) {
        return Result.ok(keywordService.add(StpAdminUtil.getLoginIdAsLong(), dto.getWord()));
    }

    @Operation(summary = "删除风控关键词（已命中的帖子不受影响，照常等审核）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REVIEW_SETTING, type = "admin")
    @DeleteMapping("/keywords/{id}")
    public Result<Void> deleteKeyword(@PathVariable Long id) {
        keywordService.delete(id);
        return Result.ok();
    }

    // ================= 举报 =================

    @Operation(summary = "举报列表（?status= 0 待处理（先举报的在前）/ 1 成立 / 2 不成立）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REPORT, type = "admin")
    @GetMapping("/reports")
    public Result<PageResult<SocialGovVOs.Report>> reports(PageQuery query, @RequestParam(required = false) Integer status) {
        return Result.ok(reportService.list(status, query));
    }

    @Operation(summary = "举报成立（同一对象上全部待处理的一起结案；action 0 不处置 / 1 隐藏帖子 / 2 删除）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REPORT, type = "admin")
    @PostMapping("/reports/{id}/uphold")
    public Result<Void> uphold(@PathVariable Long id, @Valid @RequestBody SocialGovDTOs.ReportHandle dto) {
        reportService.uphold(StpAdminUtil.getLoginIdAsLong(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "举报不成立（同一对象上全部待处理的一起结案）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_REPORT, type = "admin")
    @PostMapping("/reports/{id}/dismiss")
    public Result<Void> dismiss(@PathVariable Long id, @Valid @RequestBody SocialGovDTOs.ReportHandle dto) {
        reportService.dismiss(StpAdminUtil.getLoginIdAsLong(), id, dto);
        return Result.ok();
    }

    // ================= 禁言 =================

    @Operation(summary = "禁言记录（?volunteerId=）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_BAN, type = "admin")
    @GetMapping("/bans")
    public Result<PageResult<SocialGovVOs.Ban>> bans(PageQuery query, @RequestParam(required = false) Long volunteerId) {
        return Result.ok(banService.list(volunteerId, query));
    }

    @Operation(summary = "禁言（即时生效；scope 2 全部社区写入 / 4 禁止发帖 / 5 禁止评论 / 6 禁止点赞；天数必填）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_BAN, type = "admin")
    @PostMapping("/bans")
    public Result<Long> ban(@Valid @RequestBody SocialGovDTOs.BanSave dto) {
        return Result.ok(banService.ban(StpAdminUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "提前解除禁言")
    @SaCheckPermission(value = PermissionCode.SOCIAL_BAN, type = "admin")
    @PostMapping("/bans/{id}/lift")
    public Result<Void> lift(@PathVariable Long id, @Valid @RequestBody SocialGovDTOs.BanLift dto) {
        banService.lift(StpAdminUtil.getLoginIdAsLong(), id, dto.getReason());
        return Result.ok();
    }

    private static boolean realName() {
        return StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.SOCIAL_REAL_NAME);
    }
}
