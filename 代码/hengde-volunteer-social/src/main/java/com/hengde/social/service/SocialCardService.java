package com.hengde.social.service;

import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerSocialCardView;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 社区里「这个人显示成什么」：昵称 + 头像，<b>不露真实姓名</b>（Row 23 F 真实姓名与学校只给最高权限，属治理批的后台界面）。
 *
 * @author hengde
 */
@Service
public class SocialCardService {

    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    public Map<Long, SocialVOs.Author> volunteers(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, VolunteerSocialCardView> cards = volunteerQueryService.listSocialCardsByIds(distinct);
        Map<Long, SocialVOs.Author> out = new HashMap<>();
        for (Long id : distinct) {
            out.put(id, toAuthor(id, cards.get(id)));
        }
        return out;
    }

    public SocialVOs.Author official(String department) {
        SocialVOs.Author a = new SocialVOs.Author();
        a.setType(SocialCodes.AUTHOR_OFFICIAL);
        a.setName(StringUtils.hasText(department) ? "官方 · " + department : "官方");
        return a;
    }

    /** 主页等必须是一个存在、没注销的人。 */
    /** 企业帖的作者卡片：名称与头像取发帖时的快照（V4 爱心企业批·社区段）。 */
    public SocialVOs.Author enterprise(String name, String avatarUrl) {
        SocialVOs.Author a = new SocialVOs.Author();
        a.setType(SocialCodes.AUTHOR_ENTERPRISE);
        a.setName(name == null ? "爱心企业" : name);
        a.setAvatarUrl(avatarUrl);
        return a;
    }

    public VolunteerSocialCardView requireCard(Long volunteerId) {
        VolunteerSocialCardView card = volunteerId == null ? null
                : volunteerQueryService.listSocialCardsByIds(List.of(volunteerId)).get(volunteerId);
        if (card == null || UserStatus.DELETED.equals(card.status())) {
            throw new BusinessException("用户不存在");
        }
        return card;
    }

    static SocialVOs.Author toAuthor(Long id, VolunteerSocialCardView card) {
        SocialVOs.Author a = new SocialVOs.Author();
        a.setType(SocialCodes.AUTHOR_VOLUNTEER);
        a.setVolunteerId(id);
        if (card == null || UserStatus.DELETED.equals(card.status())) {
            a.setName("已注销用户");
            return a;
        }
        a.setName(displayName(id, card.nickName()));
        a.setAvatarUrl(card.avatarUrl());
        return a;
    }

    static String displayName(Long id, String nickName) {
        if (StringUtils.hasText(nickName)) {
            return nickName;
        }
        String s = String.valueOf(id);
        return "志愿者" + (s.length() > 4 ? s.substring(s.length() - 4) : s);
    }
}
