package com.hengde.social.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.social.service.SocialAdminService;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.constant.PermissionCode;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialOfficialPostService;
import com.hengde.social.vo.SocialVOs;
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

import java.util.Objects;

/**
 * 管理端-官方帖（V4 社区核心批，Row 23「官方：由各部分负责人在后台发布……宣传部可以删除其他部门发的帖子」）。
 *
 * @author hengde
 */
@Tag(name = "管理端-社区官方帖")
@RestController
@RequestMapping("/a/social")
public class AdminOfficialPostController {

    private SocialOfficialPostService officialPostService;
    private SocialCommentService commentService;
    private AdminQueryService adminQueryService;
    private SocialAdminService adminService;

    @Autowired
    public void setAdminService(SocialAdminService adminService) {
        this.adminService = adminService;
    }

    @Autowired
    public void setOfficialPostService(SocialOfficialPostService officialPostService) {
        this.officialPostService = officialPostService;
    }

    @Autowired
    public void setCommentService(SocialCommentService commentService) {
        this.commentService = commentService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Operation(summary = "官方帖列表（?department= 按部门筛）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_OFFICIAL, type = "admin")
    @GetMapping("/official-posts")
    public Result<PageResult<SocialVOs.OfficialPost>> list(PageQuery query, @RequestParam(required = false) String department) {
        return Result.ok(officialPostService.list(department, query));
    }

    @Operation(summary = "发布官方帖（以本账号的部门发布）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_OFFICIAL, type = "admin")
    @PostMapping("/official-posts")
    public Result<Long> publish(@Valid @RequestBody SocialDTOs.OfficialPostSave dto) {
        return Result.ok(officialPostService.publish(StpAdminUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "删除官方帖（本部门的；持有 social:official-all 可删任何部门的）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_OFFICIAL, type = "admin")
    @DeleteMapping("/official-posts/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        officialPostService.delete(adminId, departmentScope(adminId), id);
        return Result.ok();
    }

    @Operation(summary = "官方帖下的评论")
    @SaCheckPermission(value = PermissionCode.SOCIAL_OFFICIAL, type = "admin")
    @GetMapping("/official-posts/{id}/comments")
    public Result<PageResult<SocialVOs.Comment>> comments(@PathVariable Long id, PageQuery query) {
        return Result.ok(commentService.officialList(id, query));
    }

    @Operation(summary = "以官方身份评论 / 回复（只能在官方帖下）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_OFFICIAL, type = "admin")
    @PostMapping("/official-posts/{id}/comments")
    public Result<Long> comment(@PathVariable Long id, @Valid @RequestBody SocialDTOs.CommentSave dto) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        return Result.ok(commentService.officialComment(adminId, adminQueryService.departmentOf(adminId), id, dto));
    }

    @Operation(summary = "删除评论：持 social:post-manage 可删任意评论；否则只能删本部门官方帖下的（持 social:official-all 不限部门）")
    @SaCheckPermission(value = {PermissionCode.SOCIAL_OFFICIAL, PermissionCode.SOCIAL_POST_MANAGE}, mode = SaMode.OR, type = "admin")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long commentId) {
        Long adminId = StpAdminUtil.getLoginIdAsLong();
        if (StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.SOCIAL_POST_MANAGE)) {
            adminService.deleteComment(adminId, commentId);
        } else {
            commentService.officialDelete(adminId, departmentScope(adminId), commentId);
        }
        return Result.ok();
    }

    /** 持有全部门权限返回 null（不限部门）；否则返回本账号的部门（没填部门返回空串，什么也删不了）。 */
    private String departmentScope(Long adminId) {
        if (StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.SOCIAL_OFFICIAL_ALL)) {
            return null;
        }
        return Objects.toString(adminQueryService.departmentOf(adminId), "");
    }
}
