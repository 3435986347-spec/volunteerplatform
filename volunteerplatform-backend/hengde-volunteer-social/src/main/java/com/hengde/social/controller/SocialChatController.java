package com.hengde.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.dto.SocialChatDTOs;
import com.hengde.social.service.SocialChatService;
import com.hengde.social.vo.SocialChatVOs;
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

import java.util.List;

/**
 * 志愿者端-私信（{@code /v/social/chats}，Row 23「私信」「私聊」）。
 *
 * <p>实时推送走 WebSocket {@code /ws/social/chat?token=}（只收不发）；发消息、翻历史、已读都在这里。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-私信")
@RestController
@RequestMapping("/v/social/chats")
public class SocialChatController {

    private SocialChatService chatService;

    @Autowired
    public void setChatService(SocialChatService chatService) {
        this.chatService = chatService;
    }

    private static Long me() {
        return StpUtil.getLoginIdAsLong();
    }

    @Operation(summary = "我的会话列表（最后一条 + 未读数，按最后一条时间倒序）")
    @GetMapping
    public Result<PageResult<SocialChatVOs.Conversation>> conversations(PageQuery query) {
        return Result.ok(chatService.conversations(me(), query));
    }

    @Operation(summary = "未读总数（角标）")
    @GetMapping("/unread-count")
    public Result<Long> unread() {
        return Result.ok(chatService.unreadTotal(me()));
    }

    @Operation(summary = "和某个人的消息（新的在前；beforeId 往前翻）")
    @GetMapping("/{peerId}/messages")
    public Result<List<SocialChatVOs.Message>> messages(@PathVariable Long peerId,
                                                        @RequestParam(required = false) Long beforeId,
                                                        @RequestParam(required = false) Integer size) {
        return Result.ok(chatService.messages(me(), peerId, beforeId, size));
    }

    @Operation(summary = "发私信（文字与图片至少有一样；图片先经 POST /v/files/social-image 上传）")
    @PostMapping("/{peerId}/messages")
    public Result<Long> send(@PathVariable Long peerId, @Valid @RequestBody SocialChatDTOs.Send dto) {
        return Result.ok(chatService.send(me(), peerId, dto));
    }

    @Operation(summary = "标记已读（清掉我这一侧的未读）")
    @PostMapping("/{peerId}/read")
    public Result<Void> read(@PathVariable Long peerId) {
        chatService.read(me(), peerId);
        return Result.ok();
    }

    @Operation(summary = "清空聊天记录（只对我自己；后台保存的记录不受影响）")
    @DeleteMapping("/{peerId}/messages")
    public Result<Void> clear(@PathVariable Long peerId) {
        chatService.clear(me(), peerId);
        return Result.ok();
    }

    @Operation(summary = "投诉这段私聊（审核员会看到聊天内容）")
    @PostMapping("/{peerId}/reports")
    public Result<Long> report(@PathVariable Long peerId, @Valid @RequestBody SocialChatDTOs.Report dto) {
        return Result.ok(chatService.report(me(), peerId, dto));
    }
}
