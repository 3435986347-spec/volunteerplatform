package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.constant.NotifyTopic;
import com.hengde.auth.dao.VolunteerNotifyPrefMapper;
import com.hengde.auth.entity.VolunteerNotifyPref;
import com.hengde.auth.vo.NotifyPreferenceView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsNotifyTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订阅通知偏好（Row 48，V4 个人中心补全批）。
 *
 * <p><b>过滤只在 {@code SmsNotifyService} 一处做</b>（V4规划 承重条款 6）：业务调用方照常「通知谁、用哪条模板」，
 * 用户关掉了哪个话题由这里在取号码之前统一剔除——散在各个调用方判断，迟早有人漏掉且毫无征兆。</p>
 *
 * <p>本批只管<b>短信</b>：站内提示是消息记录（奖惩申诉期的起点也在那里），照常留存。</p>
 *
 * @author hengde
 */
@Service
public class NotifyPreferenceService {

    private VolunteerNotifyPrefMapper prefMapper;

    @Autowired
    public void setPrefMapper(VolunteerNotifyPrefMapper prefMapper) {
        this.prefMapper = prefMapper;
    }

    /** 我的订阅：全部话题，不可关闭的恒为开。 */
    public java.util.List<NotifyPreferenceView> mine(Long volunteerId) {
        Map<String, Integer> saved = prefMapper.selectList(Wrappers.<VolunteerNotifyPref>lambdaQuery()
                        .eq(VolunteerNotifyPref::getVolunteerId, volunteerId))
                .stream().collect(Collectors.toMap(VolunteerNotifyPref::getTopic, VolunteerNotifyPref::getSmsEnabled,
                        (a, b) -> b));
        return Arrays.stream(NotifyTopic.values())
                .map(t -> new NotifyPreferenceView(t.name(), t.getLabel(), t.isOptional(),
                        !t.isOptional() || !Objects.equals(saved.get(t.name()), 0)))
                .toList();
    }

    /** 打开 / 关闭一个话题的短信提醒。不可关闭的话题拒绝关闭（打开是空操作）。 */
    public void set(Long volunteerId, String topicName, boolean smsEnabled) {
        if (volunteerId == null) {
            throw new BusinessException("未登录");
        }
        NotifyTopic topic = NotifyTopic.fromName(topicName);
        if (topic == null) {
            throw new BusinessException("没有这个提醒：" + topicName);
        }
        if (!topic.isOptional()) {
            if (!smsEnabled) {
                throw new BusinessException("「" + topic.getLabel() + "」的提醒不能关闭");
            }
            return;
        }
        prefMapper.upsert(volunteerId, topic.name(), smsEnabled ? 1 : 0);
    }

    /**
     * 这批志愿者里关掉了这条模板所属话题的那些 id。话题不可关闭时恒为空集（存过关闭也不生效）。一次查库。
     */
    public Set<Long> optedOut(Collection<Long> volunteerIds, SmsNotifyTemplate template) {
        NotifyTopic topic = NotifyTopic.of(template);
        if (!topic.isOptional() || volunteerIds == null || volunteerIds.isEmpty()) {
            return Set.of();
        }
        return prefMapper.selectList(Wrappers.<VolunteerNotifyPref>lambdaQuery()
                        .select(VolunteerNotifyPref::getVolunteerId)
                        .eq(VolunteerNotifyPref::getTopic, topic.name())
                        .eq(VolunteerNotifyPref::getSmsEnabled, 0)
                        .in(VolunteerNotifyPref::getVolunteerId, volunteerIds))
                .stream().map(VolunteerNotifyPref::getVolunteerId).collect(Collectors.toCollection(HashSet::new));
    }
}
