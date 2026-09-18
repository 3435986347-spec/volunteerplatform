package com.hengde.auth.service;

import jakarta.annotation.PostConstruct;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.common.sms.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知类短信的统一发送入口：业务侧只说「通知谁、用哪条模板、填什么值」。
 *
 * <p>把号码解密、模板解析、事务时机、异步派发、失败处理这五件事收在一处，
 * 业务代码里因此只留下一行调用——这条链路上每一处都有一个「不这么做就会出事」的理由，
 * 散到各个 service 去写必然会漏掉其中某一条。</p>
 *
 * <h3>为什么放在 auth</h3>
 * <p>手机号在库里是密文，解密要 {@code CryptoUtil}，而它归 auth。activity / organization / honor
 * 三个模块本来就都依赖 auth，放这里不产生新的依赖方向，更不会成环。</p>
 *
 * <h3>为什么一定要等事务提交之后再发</h3>
 * <p><b>短信发出去就撤不回。</b>业务事务在通知之后仍可能回滚（CAS 落空、后续步骤抛异常、
 * 上层再包一层事务），那时志愿者手里已经躺着一条「您的报名已通过审核」，而库里什么都没变——
 * 这种错误没有任何补救手段。</p>
 * <p>这与站内提示<b>刻意同事务</b>并不矛盾，恰恰相反：站内记录跟着事务一起回滚会干净消失，
 * 所以它可以（也应该）同事务；短信不可回滚，所以它必须等提交。两者的处置不同，
 * 是因为「能不能撤回」这个性质不同。</p>
 *
 * <h3>通知失败绝不影响业务</h3>
 * <p>模板没配、志愿者没手机号、火山拒收——一律记日志后返回，不抛。
 * 审核通过是已经发生的事实，不能因为短信没发出去就变成没通过。
 * <b>唯一会抛的是参数个数不对</b>（{@link SmsNotifyTemplate#params}），那是编码错误，
 * 应当在用例里当场炸出来，而不是留到线上变成一条内容缺一块的短信。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SmsNotifyService {

    /**
     * 当前<b>真的有业务调用方</b>的模板，仅用于启动时的配置自检。
     *
     * <p>另外 9 条已报备但对应功能尚未开发（见 {@link SmsNotifyTemplate} 各条 javadoc），
     * 它们没配模板 ID 是正常状态，不该在启动日志里天天报警——那种噪音看几次就没人看了。</p>
     */
    private static final Set<SmsNotifyTemplate> WIRED = EnumSet.of(
            SmsNotifyTemplate.ENROLLMENT_APPROVED,
            SmsNotifyTemplate.ENROLLMENT_REJECTED,
            SmsNotifyTemplate.ENROLLMENT_POSITION,
            SmsNotifyTemplate.ACTIVITY_START_REMINDER,
            SmsNotifyTemplate.ACTIVITY_CANCELLED,
            SmsNotifyTemplate.ACTIVITY_COMMENT_REMINDER,
            SmsNotifyTemplate.SERVICE_RECORD_CREDITED,
            SmsNotifyTemplate.REWARD_PUNISH,
            SmsNotifyTemplate.ACTIVITY_VIOLATION,
            SmsNotifyTemplate.ORG_JOIN_RESULT,
            SmsNotifyTemplate.GROUP_JOIN_RESULT,
            // V3 卷批补接的商城审核结果——当时漏了登记到这里（也漏了 yaml 与部署模板），配置缺失时启动不报警
            SmsNotifyTemplate.POINTS_ORDER_REVIEW,
            // V4 投诉建议批
            SmsNotifyTemplate.COMPLAINT_REPLIED);

    private VolunteerQueryService volunteerQueryService;
    private SmsProperties smsProperties;
    private SmsDispatcher smsDispatcher;
    private NotifyPreferenceService notifyPreferenceService;

    @Autowired
    public void setNotifyPreferenceService(NotifyPreferenceService notifyPreferenceService) {
        this.notifyPreferenceService = notifyPreferenceService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setSmsProperties(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
    }

    @Autowired
    public void setSmsDispatcher(SmsDispatcher smsDispatcher) {
        this.smsDispatcher = smsDispatcher;
    }

    /** 通知一位志愿者。id 为空直接返回——调用方那边已经校验过对象存在，这里再抛只会指错地方。 */
    public void notifyVolunteer(Long volunteerId, SmsNotifyTemplate template, Map<String, String> params) {
        if (volunteerId == null) {
            return;
        }
        notifyVolunteers(List.of(volunteerId), template, params);
    }

    /**
     * 通知一批志愿者（同一条模板、同一组参数，如「活动取消」通知全体报名者）。
     *
     * <p>每人参数不同的场景（如各自的服务时长与积分）请逐个调 {@link #notifyVolunteer}。</p>
     */
    public void notifyVolunteers(Collection<Long> volunteerIds, SmsNotifyTemplate template,
                                 Map<String, String> params) {
        if (template == null || volunteerIds == null || volunteerIds.isEmpty()) {
            return;
        }
        requireParams(template, params);

        String templateId = smsProperties.getTemplates().get(template.getKey());
        if (!StringUtils.hasText(templateId)) {
            // 只 warn 不抛：漏配一条模板不该让报名审核也做不成
            log.warn("[SMS-NOTIFY] 模板 {} 未配置 ID，本次通知跳过（收件人 {} 位）",
                    template.getKey(), volunteerIds.size());
            return;
        }

        // 用户在「订阅通知」里关掉了这个话题的，在取号码之前统一剔除（不可关闭的话题这里恒为空集）
        Set<Long> optedOut = notifyPreferenceService.optedOut(volunteerIds, template);
        List<String> phones = resolvePhones(optedOut.isEmpty() ? volunteerIds
                : volunteerIds.stream().filter(id -> !optedOut.contains(id)).toList());
        if (phones.isEmpty()) {
            log.debug("[SMS-NOTIFY] 模板 {} 无有效手机号，跳过", template.getKey());
            return;
        }

        // 快照一份参数：真正发送发生在提交之后，那时调用方的局部变量可能已被复用或改写。
        // 现有调用方传的都是 SmsNotifyTemplate#params 新建的 map，此处只是不把这条假设留给将来的人。
        Map<String, String> snapshot = Map.copyOf(params);
        afterCommit(() -> smsDispatcher.dispatch(phones, templateId, snapshot, template.getKey()));
    }

    /**
     * 取号码：去重、去空、去掉不该再被打扰的账号。
     *
     * <p>「谁还该收到短信」这个口径落在 {@code VolunteerQueryService#listNotifiablePhones}——
     * 它属于账号状态语义，归 auth 一处说了算：<b>已注销不发、禁用照发</b>（理由见那里）。
     * 游客与没绑手机号的自然不在返回值里，通知不到不是错误。</p>
     */
    private List<String> resolvePhones(Collection<Long> volunteerIds) {
        Set<Long> ids = new LinkedHashSet<>(volunteerIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> phoneById = volunteerQueryService.listNotifiablePhones(ids);
        // 按传入顺序去重：同一个人报了多个场次时调用方很容易凑出重复 id
        Set<String> phones = new LinkedHashSet<>();
        for (Long id : ids) {
            String phone = phoneById.get(id);
            if (StringUtils.hasText(phone)) {
                phones.add(phone);
            }
        }
        return new ArrayList<>(phones);
    }

    /**
     * 参数键必须与模板声明的变量<b>完全一致</b>。
     *
     * <p>火山对多出来的键会忽略、对缺失的键只留个空洞，两种情况都不报错——
     * 于是短信照发、内容缺一块、还没有任何征兆。这里宁可直接抛：
     * 键对不上属于编码错误，正常路径下参数由 {@link SmsNotifyTemplate#params} 生成，抛不出来。</p>
     */
    private static void requireParams(SmsNotifyTemplate template, Map<String, String> params) {
        Set<String> expected = new LinkedHashSet<>(template.getVariables());
        Set<String> actual = (params == null) ? Set.of() : params.keySet();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("模板 " + template.getKey()
                    + " 的参数不匹配：期望 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 有事务就等提交后执行，没事务就当场执行。
     *
     * <p>用 {@code afterCommit} 而不是 {@code afterCompletion}：回滚时什么都不该发。</p>
     */
    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * 启动时把「已接业务但没配模板 ID」的那几条列出来。
     *
     * <p>不这么做的话，漏配只会在某位志愿者本该收到短信的那一刻变成一行 warn，
     * 而那时没有人在看日志。只在真实发送开启时检查——开发/测试环境本来就不配模板。</p>
     */
    @PostConstruct
    void warnUnconfiguredTemplates() {
        if (!smsProperties.isEnabled()) {
            return;
        }
        List<String> missing = WIRED.stream()
                .map(SmsNotifyTemplate::getKey)
                .filter(key -> !StringUtils.hasText(smsProperties.getTemplates().get(key)))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("[SMS-NOTIFY] 以下通知模板已接入业务但未配置模板 ID，相关短信不会发出：{}", missing);
        }
    }
}
