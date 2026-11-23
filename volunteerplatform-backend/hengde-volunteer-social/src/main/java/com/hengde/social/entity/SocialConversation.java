package com.hengde.social.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 私信会话（V82，一对人一条）。
 *
 * <p><b>两人 id 排序后存 small / large</b>：不规范化就会出现 (A,B) 与 (B,A) 两条，未读数与最后一条各记一半。
 * 「我」是哪一边由 {@link #isSmall(Long)} 判断，未读 / 清空水位 / 已发条数三对列都按这个分边。</p>
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("social_conversation")
public class SocialConversation {
    private Long id;
    private Long smallId;
    private Long largeId;
    private Long lastMessageId;
    private String lastContent;
    private Long lastSenderId;
    private LocalDateTime lastTime;
    private Integer smallUnread;
    private Integer largeUnread;
    private Long smallClearedId;
    private Long largeClearedId;
    private Integer smallSent;
    private Integer largeSent;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public boolean isSmall(Long volunteerId) {
        return smallId != null && smallId.equals(volunteerId);
    }

    /** 这个人的未读数。 */
    public int unreadOf(Long volunteerId) {
        Integer n = isSmall(volunteerId) ? smallUnread : largeUnread;
        return n == null ? 0 : n;
    }

    /** 这个人的「清空聊天记录」水位（只对他自己生效）。 */
    public long clearedIdOf(Long volunteerId) {
        Long n = isSmall(volunteerId) ? smallClearedId : largeClearedId;
        return n == null ? 0L : n;
    }

    /** 这个人在这条会话里发过多少条。 */
    public int sentOf(Long volunteerId) {
        Integer n = isSmall(volunteerId) ? smallSent : largeSent;
        return n == null ? 0 : n;
    }

    /** 对方是谁。 */
    public Long peerOf(Long volunteerId) {
        return isSmall(volunteerId) ? largeId : smallId;
    }
}
