package com.hengde.social.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.constant.PermissionCode;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.service.SocialChatAdminService;
import com.hengde.social.vo.SocialChatVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * 管理端-聊天记录与私聊工单（Row 23 F「所有聊天记录均保存在系统后台、查看权限仅为最高管理员，亦可由管理员下放」）。
 *
 * <p>全部挂 {@code social:chat-view}，**默认不授任何人**（超管通配）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-聊天记录")
@RestController
@RequestMapping("/a/social")
public class AdminSocialChatController {

    private SocialChatAdminService chatAdminService;

    @Autowired
    public void setChatAdminService(SocialChatAdminService chatAdminService) {
        this.chatAdminService = chatAdminService;
    }

    @Operation(summary = "会话列表（?volunteerId= 只看某个人的）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_CHAT_VIEW, type = "admin")
    @GetMapping("/chats")
    public Result<PageResult<SocialChatVOs.AdminConversation>> conversations(
            @RequestParam(required = false) Long volunteerId, PageQuery query) {
        return Result.ok(chatAdminService.conversations(volunteerId, query));
    }

    @Operation(summary = "一条会话的全部消息（含双方各自清空的、已删除的）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_CHAT_VIEW, type = "admin")
    @GetMapping("/chats/{id}/messages")
    public Result<PageResult<SocialChatVOs.AdminMessage>> messages(@PathVariable Long id, PageQuery query) {
        return Result.ok(chatAdminService.messages(id, query));
    }

    @Operation(summary = "删除一条违规消息（双方都看不到；后台仍查得到）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_CHAT_VIEW, type = "admin")
    @DeleteMapping("/chat-messages/{id}")
    public Result<Void> deleteMessage(@PathVariable Long id) {
        chatAdminService.deleteMessage(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "私聊工单队列（?status= 0待处理/1成立/2不成立；关键词命中的插队在前）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_CHAT_VIEW, type = "admin")
    @GetMapping("/chat-reports")
    public Result<PageResult<SocialChatVOs.ChatReport>> reports(
            @Parameter(description = "0待处理/1成立/2不成立") @RequestParam(required = false) Integer status, PageQuery query) {
        return Result.ok(chatAdminService.reports(status, query));
    }

    @Operation(summary = "处理工单（成立 / 不成立；成立可顺带删掉那条消息。禁言另走 POST /a/social/bans）")
    @SaCheckPermission(value = PermissionCode.SOCIAL_CHAT_VIEW, type = "admin")
    @PostMapping("/chat-reports/{id}/handle")
    public Result<Void> handle(@PathVariable Long id, @Valid @RequestBody SocialChatDTOs.Handle dto) {
        chatAdminService.handle(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
