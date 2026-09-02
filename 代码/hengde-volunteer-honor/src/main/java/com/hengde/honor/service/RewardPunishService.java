package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.activity.service.ViolationReviewService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.NotificationService;
import com.hengde.auth.service.SanctionService;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerGrantEligibilityView;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.dao.HonorRewardPunishMapper;
import com.hengde.honor.dto.AppealHandleDTO;
import com.hengde.honor.dto.AppealSubmitDTO;
import com.hengde.honor.dto.RewardPunishSaveDTO;
import com.hengde.honor.entity.HonorRewardPunish;
import com.hengde.honor.vo.RewardPunishVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 奖惩中心（V2 第 5 批）。
 *
 * <p><b>需求原文</b>（xlsx Row 41）：C「各类违规记录和奖励」；
 * F「各类违规记录和奖励均需<b>组织部同学审核才可显示</b>，审核之后，
 * 志愿者会收到提示，并有 <b>7 天申诉期</b>」。原型 P109 给出完整卡片与详情形态。</p>
 *
 * <p><b>三条效力都挂在「审核通过」这一刻</b>：对志愿者可见、积分加减入账、处置开始生效。
 * 待审核期间它只是一张草稿——这正是 Row 41 F「审核才可显示」的意思，
 * 也避免了「先罚后审、审不过再退回去」这种要冲正三处状态的麻烦。</p>
 *
 * @author hengde
 */
@Service
public class RewardPunishService {

    private static final Logger log = LoggerFactory.getLogger(RewardPunishService.class);

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 编号撞 {@code uk_rp_no} 时的换号重试次数。
     *
     * <p>与证书编号同一形状、同一教训：编号是「秒 + 6 位随机」，同秒内只有 10^6 个取值，
     * 批量开单时碰撞不是理论问题；原样抛出会让那一条静默失败。</p>
     */
    private static final int NO_MAX_ATTEMPTS = 5;

    /** 单张奖惩单的积分变动绝对值上限。见 {@code create} 里为什么必须有界 */
    private static final int MAX_POINTS_DELTA = 1_000_000;

    /**
     * 单条处置的最长天数。<b>复用 auth 侧的同一个常量，不在这里另写一个数</b>——
     * 真正的兜底在 {@code SanctionService.impose} 与 V33 的 {@code ck_rp_sanction_days}，
     * 本处只是把报错提前到开单那一步、给人话文案。两处各写一个 3650 迟早会分叉。
     */
    private static final int MAX_SANCTION_DAYS = VolunteerSanction.MAX_SANCTION_DAYS;

    /** 提示正文里的申诉截止时刻格式；与 {@code SanctionQueryService} 报错文案同一口径 */
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private HonorRewardPunishMapper rewardPunishMapper;
    private ViolationReviewService violationReviewService;
    private VolunteerQueryService volunteerQueryService;
    private PointService pointService;
    private SanctionService sanctionService;
    private NotificationService notificationService;
    private SmsNotifyService smsNotifyService;
    private HonorProperties honorProperties;

    @Autowired
    public void setRewardPunishMapper(HonorRewardPunishMapper rewardPunishMapper) {
        this.rewardPunishMapper = rewardPunishMapper;
    }

    @Autowired
    public void setViolationReviewService(ViolationReviewService violationReviewService) {
        this.violationReviewService = violationReviewService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Autowired
    public void setSanctionService(SanctionService sanctionService) {
        this.sanctionService = sanctionService;
    }

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    // ---------- ① 开单 ----------

    /**
     * 后台开一张奖惩单，落「待审核」。
     *
     * <p><b>处罚可由一条现场违规转来</b>（{@code violationId}），但那条违规<b>必须已通过组织部审核</b>——
     * 未经核实的现场记录只是负责人的一面之词，不够格作为处罚依据（Row 41 F / Row 59）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(RewardPunishSaveDTO dto, Long adminId) {
        int type = dto.getType();
        if (type != HonorRewardPunish.TYPE_REWARD && type != HonorRewardPunish.TYPE_PUNISH) {
            throw new BusinessException("奖惩类型只能是 1奖励 或 2处罚");
        }
        // 奖励为正、处罚为负或 0：符号写反会让「处罚」给人加分，而列表上仍显示为处罚
        Integer delta = dto.getPointsDelta() == null ? 0 : dto.getPointsDelta();
        if (type == HonorRewardPunish.TYPE_REWARD && delta < 0) {
            throw new BusinessException("奖励的积分变动不能为负");
        }
        if (type == HonorRewardPunish.TYPE_PUNISH && delta > 0) {
            throw new BusinessException("处罚的积分变动不能为正");
        }
        if (type == HonorRewardPunish.TYPE_REWARD
                && (dto.getSanctionScope() != null || dto.getViolationId() != null)) {
            // 与 ck_rp_sanction_only_punish / ck_rp_violation_only_punish 同一口径，
            // 在这里先拦一道是为了给出人话报错，而不是让用户看到一句数据库约束名
            throw new BusinessException("奖励不能附带处置或关联违规记录");
        }
        Long volunteerId = dto.getVolunteerId();
        HonorRewardPunish rp = new HonorRewardPunish();
        if (dto.getViolationId() != null) {
            ViolationReviewService.ApprovedViolation v =
                    violationReviewService.findApproved(dto.getViolationId());
            if (v == null) {
                throw new BusinessException("该违规记录不存在或尚未通过组织部审核，不能据此开处罚单");
            }
            // 归属以违规记录为准，不采信入参——否则可以拿甲的违规去罚乙
            volunteerId = v.volunteerId();
            rp.setActivityId(v.activityId());
            rp.setSlotId(v.slotId());
        } else {
            rp.setActivityId(dto.getActivityId());
            rp.setSlotId(dto.getSlotId());
        }
        if (volunteerId == null) {
            throw new BusinessException("请指定志愿者");
        }
        // 【志愿者必须存在】奖惩单没有外键（跨模块表，本项目一贯不建跨域外键）。
        // 不校验的话，id 打错一位就会开出一张挂在不存在的人名下的单，
        // 审核通过时还会照样入账一笔积分——而那笔分永远没有主人，也没人会来投诉。
        // 【用 getProfileForEligibility 而不是 listNamesByIds】后者会把 realName 为空的行滤掉，
        // 拿它判存在性会把「真实存在但尚未实名」的志愿者判成不存在——而未实名的人照样会违规。
        // 【这里用快照读就够】开单只落一张待审草稿，不产生任何权益；真正要防的窗口在审核那一刻，
        // 由 requireApprovableVolunteer 用当前读兜住。
        VolunteerProfileView profile = volunteerQueryService.getProfileForEligibility(volunteerId);
        if (profile == null) {
            throw new BusinessException("志愿者不存在：" + volunteerId);
        }
        // 已注销的账号不该再开单：这张单永远审不过（审核会拒），留着只是一条谁也处理不掉的待办
        if (UserStatus.DELETED.equals(profile.status())) {
            throw new BusinessException("该志愿者账号已注销，不能开奖惩单：" + volunteerId);
        }
        // 【积分幅度必须有界】申诉成立要写一笔 -delta 的反向流水，而 -Integer.MIN_VALUE
        // 仍是 Integer.MIN_VALUE（溢出），那张单将【永远冲正不回来】。
        // 顺带也挡住手滑多敲几个零。
        if (Math.abs((long) delta) > MAX_POINTS_DELTA) {
            throw new BusinessException("积分变动绝对值不能超过 " + MAX_POINTS_DELTA);
        }
        if (dto.getSanctionScope() != null) {
            SanctionScope.assertKnown(dto.getSanctionScope());
            if (dto.getSanctionDays() != null
                    && (dto.getSanctionDays() <= 0 || dto.getSanctionDays() > MAX_SANCTION_DAYS)) {
                // 上限不只是防手滑：now.plusDays(Integer.MAX_VALUE) 会直接抛 DateTimeException，
                // 而稍小一点的值会算出一个「看着有期限、实际等同永久」的到期日——
                // 那种处罚应当走「不设期限」明说，而不是用 99999 天伪装成有期限。
                throw new BusinessException("限制天数须在 1~" + MAX_SANCTION_DAYS + " 之间；不设期限请不传天数");
            }
        } else if (dto.getSanctionDays() != null) {
            // 只填天数不填能力域，落库会得到一条「有期限但什么也不限制」的单，
            // 而详情页会照着 sanctionDays 印出「限制 7 天」——看着被罚了，实际没有任何约束。
            throw new BusinessException("填了限制天数就必须选择限制范围");
        }
        rp.setVolunteerId(volunteerId);
        rp.setType(type);
        rp.setCategory(dto.getCategory());
        rp.setTitle(dto.getTitle());
        rp.setDescription(dto.getDescription());
        rp.setPointsDelta(delta);
        rp.setViolationId(dto.getViolationId());
        rp.setSanctionScope(dto.getSanctionScope());
        rp.setSanctionDays(dto.getSanctionDays());
        rp.setReviewStatus(HonorRewardPunish.REVIEW_PENDING);
        rp.setAppealStatus(HonorRewardPunish.APPEAL_NONE);
        rp.setCreateBy(adminId);
        insertWithNewNo(rp);
        return rp.getId();
    }

    /**
     * 插入并在编号撞键时换号重试。
     *
     * <p>撞 {@code uk_active_violation}（同一条违规已开过单）要原样报出来——那是业务冲突，
     * 换多少个编号都不会变；只有撞 {@code uk_rp_no} 才该重试。两者靠「换号后是否还撞」区分：
     * 先把违规是否已开单查清楚，剩下的撞键就只可能是编号。</p>
     *
     * <p><b>预查必须与 {@code uk_active_violation} 同口径</b>，否则 Java 层会把库已经放行的重开
     * 挡在门外，而且报的是一句「请勿重复开单」，看不出真正原因。当前口径为两条释放条件：</p>
     * <ul>
     *   <li>V35 起被<b>驳回</b>的单不占位——开单人填错了才有改正的余地，
     *       {@code reject} 强制填写的那条原因本就是给他看的；</li>
     *   <li>V38 起<b>申诉成立</b>的单不占位——协会 2026-08-11 第 8 条
     *       「申诉成立…可以给他开第二张轻一点的处罚单」。</li>
     * </ul>
     */
    private void insertWithNewNo(HonorRewardPunish rp) {
        if (rp.getViolationId() != null && rewardPunishMapper.selectCount(
                Wrappers.<HonorRewardPunish>lambdaQuery()
                        .eq(HonorRewardPunish::getViolationId, rp.getViolationId())
                        .ne(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_REJECTED)
                        .ne(HonorRewardPunish::getAppealStatus, HonorRewardPunish.APPEAL_UPHELD)) > 0) {
            throw new BusinessException("该违规记录已经开过处罚单，请勿重复开单");
        }
        for (int attempt = 0; ; attempt++) {
            rp.setRpNo(nextNo());
            try {
                rewardPunishMapper.insert(rp);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= NO_MAX_ATTEMPTS - 1) {
                    throw e;
                }
                // 【必须区分撞的是哪个键】上面那次预查是快照读，并发下两个人可以同时通过它、
                // 同时插入同一条违规的处罚单，于是撞的是 uk_active_violation 而不是编号。
                // 那种冲突换多少个号都不会变，重试五次只是把一句人话报错拖成一个数据库异常。
                if (isViolationConflict(e)) {
                    throw new BusinessException("该违规记录已经开过处罚单，请勿重复开单");
                }
                log.warn("奖惩编号碰撞，换号重试 attempt={} volunteerId={}", attempt + 1, rp.getVolunteerId());
                rp.setId(null);
            }
        }
    }

    /** 撞的是不是「同一条违规已开过单」那个唯一键。 */
    private static boolean isViolationConflict(DuplicateKeyException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("uk_active_violation");
    }

    /** 编号：秒级时间戳 + 6 位随机，形态参考 P109「处罚编号：251363568415641」。 */
    private String nextNo() {
        return LocalDateTime.now().format(NO_TIME)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    // ---------- ② 审核 ----------

    /**
     * 审核通过：此刻起对志愿者可见、积分入账、处置生效、7 天申诉期开始计时。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Long adminId) {
        HonorRewardPunish rp = requireForUpdate(id);
        requireApprovableVolunteer(rp);
        // 【必须截到秒】appeal_deadline 是 DATETIME(fsp=0)，而 MySQL 对小数秒是【四舍五入】不是截断：
        // 16:35:59.7 落库会变成 16:36:00，而下面那条站内提示是拿内存里这个值格式化的，写着「16:35」。
        // 于是告知志愿者的截止时刻比实际执行的早一分钟——申诉期是对志愿者的承诺，两个数字必须是同一个。
        // 【同一个坑的第二处】SanctionService.impose 早就为 effective_time 截过一次
        // （见 V2规划 第 5 批「一个实测修掉的坑」），当时只修了那一处；本处是漏网的另一处。
        // 判据：凡是【写进 DATETIME 又要拿内存值对外展示或比较】的时刻，都要先截。
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime deadline = now.plusDays(honorProperties.getRewardPunish().getAppealDays());
        // CAS：只有仍待审核的单可以被裁决。两人同时点，后一个必须落空而不是覆盖前一个的结论。
        int rows = rewardPunishMapper.update(null, Wrappers.<HonorRewardPunish>lambdaUpdate()
                .set(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_APPROVED)
                .set(HonorRewardPunish::getReviewedBy, adminId)
                .set(HonorRewardPunish::getReviewTime, now)
                // 申诉截止【落库定死】：现算意味着哪天把 7 改成 3，在途的申诉权会被追溯性缩短
                .set(HonorRewardPunish::getAppealDeadline, deadline)
                .set(HonorRewardPunish::getUpdateTime, now)
                .eq(HonorRewardPunish::getId, id)
                .eq(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_PENDING));
        if (rows != 1) {
            throw new BusinessException("该奖惩单不存在或已被审核");
        }
        // 积分：审核通过才入账。source_id = 单据 id，uk_source(6, id) 保幂等（重复审核已被上面的 CAS 挡住）
        if (rp.getPointsDelta() != null && rp.getPointsDelta() != 0) {
            pointService.record(rp.getVolunteerId(), rp.getPointsDelta(),
                    PointSourceType.REWARD_PUNISH, id, pointRemark(rp),
                    PointSourceType.OPERATOR_ADMIN, adminId);
        }
        // 处置：同样到审核通过才真正施加
        if (rp.getSanctionScope() != null) {
            sanctionService.impose(rp.getVolunteerId(), VolunteerSanction.SOURCE_REWARD_PUNISH, id,
                    rp.getSanctionScope(), rp.getSanctionDays());
        }
        // 提示：Row 41 F「审核之后，志愿者会收到提示，并有 7 天申诉期」的前半句。
        // 【与本事务同生共死】审核回滚了提示不该留下，提示失败了审核也不该算数——
        // 那条提示是申诉期的起点，收不到就等于申诉权没有被告知。故不做异步、不吞异常。
        notificationService.notify(rp.getVolunteerId(),
                VolunteerNotification.TYPE_REWARD_PUNISH_APPROVED,
                approvedTitle(rp), approvedContent(rp, deadline),
                VolunteerNotification.BIZ_REWARD_PUNISH, id);
        notifyApprovedBySms(rp);
    }

    /**
     * 审核通过的短信提示（{@code reward-punish}）。
     *
     * <p>Row 41 F 那句「志愿者会收到提示」此前只落了站内一半——要打开小程序才看得见。
     * 站内那条与本事务同生共死（它可回滚），短信则由 {@code SmsNotifyService} 推迟到<b>提交之后</b>
     * 才真正发出（它撤不回）。两条走不同时机，是因为「能不能撤回」这个性质不同。</p>
     *
     * <p><b>⚠️ 短信里说不出申诉截止日期</b>：协会报备的这条模板只有 {@code type/title/points}
     * 三个占位，没有放日期的地方。而 Row 41 F 的原话是「并有 7 天申诉期」——
     * 只说「您有一条处罚记录」不说到几号，等于把有期限的权利说成没期限的。
     * 站内提示写了准确到秒的截止时刻（{@code approvedContent}），短信只能引导他去看。
     * 已作为问题发给协会：补报一条带 {@code ${deadline}} 的模板后，把这里换掉即可。</p>
     *
     * <p>失败只记日志：审核通过已经落库、积分已入账、处置已施加，不能因短信没发出去而回滚。</p>
     */
    private void notifyApprovedBySms(HonorRewardPunish rp) {
        try {
            boolean reward = Integer.valueOf(HonorRewardPunish.TYPE_REWARD).equals(rp.getType());
            Integer delta = rp.getPointsDelta();
            smsNotifyService.notifyVolunteer(rp.getVolunteerId(), SmsNotifyTemplate.REWARD_PUNISH,
                    SmsNotifyTemplate.REWARD_PUNISH.params(
                            reward ? "奖励" : "处罚",
                            rp.getTitle(),
                            String.valueOf(delta == null ? 0 : delta)));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 奖惩审核通知失败 rewardPunishId={}", rp.getId(), ex);
        }
    }

    /** 提示标题：一眼看出是奖是惩。 */
    private static String approvedTitle(HonorRewardPunish rp) {
        return Integer.valueOf(HonorRewardPunish.TYPE_REWARD).equals(rp.getType())
                ? "您收到一条奖励记录" : "您收到一条处罚记录";
    }

    /**
     * 提示正文。
     *
     * <p><b>处罚必须写明申诉截止时刻</b>：Row 41 F 给的是「7 天申诉期」，
     * 只说「您可以申诉」而不说到几号，等于把一个有期限的权利说成了没期限的——
     * 而那个时刻已经落库定死（{@code appeal_deadline}），这里直接用它，不另算一遍。</p>
     *
     * <p>奖励没有申诉入口（P109 的奖励卡片只有「查看详情」），故不提申诉。</p>
     */
    private static String approvedContent(HonorRewardPunish rp, LocalDateTime deadline) {
        String what = (rp.getCategory() == null ? "" : rp.getCategory())
                + (rp.getTitle() == null || rp.getTitle().isBlank() ? "" : "（" + rp.getTitle() + "）");
        if (Integer.valueOf(HonorRewardPunish.TYPE_REWARD).equals(rp.getType())) {
            return "奖励「" + what + "」已通过组织部审核，可在奖惩记录中查看详情。";
        }
        return "处罚「" + what + "」已通过组织部审核并即时生效，可在奖惩记录中查看详情。"
                + "如有异议，请在 " + deadline.format(DEADLINE_FMT) + " 前提交申诉。";
    }

    /**
     * 审核通过那一刻复核志愿者，<b>当前读 + 排他锁</b>。
     *
     * <p><b>为什么不能用 {@code getProfileForEligibility}</b>：它是普通 {@code selectById}，
     * 在 REPEATABLE READ 下读的是本事务快照——而本方法已经先读过奖惩单，读视图那时就定死了，
     * 之后别人把志愿者注销/删除<b>并提交</b>，这里照样看到「正常」。开单与审核之间往往隔着几天，
     * 这个窗口不是理论值。与 {@code MedalGrantService.requireGrantableVolunteerForShare} 同一条教训。</p>
     *
     * <p><b>为什么取 X 而不是 S</b>：处罚单在本事务里紧接着还要写 {@code volunteer_sanction}，
     * 而 {@code SanctionService.impose} 也要锁志愿者这一行。先 S 后 X 是锁升级，
     * 两名管理员同时审同一个人的两张单会直接死锁。详见 {@code VolunteerMapper.selectByIdForUpdate}。</p>
     *
     * <p><b>三种情形分开处置</b>：</p>
     * <ul>
     *   <li><b>行已不存在</b>（含逻辑删除）→ 拒绝。积分与处置都会落到一个没有主人的 id 上。</li>
     *   <li><b>已注销</b>（{@code status=2}）→ 拒绝。账号是用户自己注销的，
     *       给他加分没有意义，限制他更没有对象；这条正是「非 null 就放行」漏掉的那一格。</li>
     *   <li><b>已禁用</b>（{@code status=1}）→ <b>放行</b>，理由见下。</li>
     * </ul>
     *
     * <p><b>🔁 这一格来回改过两次，两次的理由都留在这里，因为它们并不互相反驳。</b></p>
     *
     * <p><b>第 6 轮评审：改成「禁用期间一律不批」。</b>当时的事实是 {@code SaTokenConfigure}
     * 对 {@code status != NORMAL} 的账号拦掉<b>全部</b> {@code /v/**}，其中就包括
     * {@code GET /v/honor/reward-punishes} 与 {@code POST /v/honor/reward-punishes/&#123;id&#125;/appeal}。
     * 于是「审核通过 + 7 天申诉期开始计时」这个动作，对一个禁用账号来说是
     * <b>处罚立即生效、而申诉期在他够不到的地方流逝</b>；禁用超过 7 天，申诉权就在不可达状态下过期了。
     * 「被罚得最重的人恰恰成了唯一无法申诉的人」是本项目明确拒绝的形态
     * （Row 41 F 给的是<b>申诉期</b>，不是一段倒计时）。</p>
     *
     * <p><b>协会 2026-08-11 第 5 条：改回放行，同时开口子。</b>原文「账号类处罚：由理事会审核后
     * 才生效，<b>只给禁用账号开个小口子、只能看奖惩和提申诉</b>」。
     * <b>上一段的理由并没有被推翻</b>——要消除的仍然是「申诉期在够不到的地方流逝」，
     * 只是手段从「不批」换成了「让他够得到」：{@code VolunteerAuthService.ensureLoginable}
     * 现在给禁用账号发 token，{@code BannedAccountGate.EXEMPT_PATHS} 放行奖惩记录、申诉、
     * 处置查看与站内提示。<b>是前提变了，结论才跟着变</b>；哪天有人想把这条 {@code if} 加回来，
     * 先去确认那个前提是不是又变回去了。</p>
     *
     * <p><b>顺带关掉了一个当时关不掉的窗口</b>：审核通过之后账号<b>才</b>被禁用，申诉期同样会在
     * 不可达中流逝——那本来需要「禁用期间暂停计时」或「解禁时顺延」这类跨模块规则。
     * 口子一开，申诉随时可达，这个窗口自动消失，两条规则都不必做
     * （《协会待确认清单》第 7-② 条已据此关闭）。</p>
     */
    private void requireApprovableVolunteer(HonorRewardPunish rp) {
        VolunteerGrantEligibilityView v =
                volunteerQueryService.getGrantEligibilityForUpdate(rp.getVolunteerId());
        if (v == null) {
            throw new BusinessException("该志愿者已不存在，无法通过审核；请驳回本单");
        }
        if (UserStatus.DELETED.equals(v.status())) {
            throw new BusinessException("该志愿者账号已注销，无法通过审核；请驳回本单");
        }
    }

    /** 审核驳回：志愿者始终看不到这张单，不入账、不生效。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long adminId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("驳回必须填写原因");
        }
        int rows = rewardPunishMapper.update(null, Wrappers.<HonorRewardPunish>lambdaUpdate()
                .set(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_REJECTED)
                .set(HonorRewardPunish::getReviewedBy, adminId)
                .set(HonorRewardPunish::getReviewTime, LocalDateTime.now())
                .set(HonorRewardPunish::getRejectReason, reason)
                .set(HonorRewardPunish::getUpdateTime, LocalDateTime.now())
                .eq(HonorRewardPunish::getId, id)
                .eq(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_PENDING));
        if (rows != 1) {
            throw new BusinessException("该奖惩单不存在或已被审核");
        }
    }

    // ---------- ③ 申诉 ----------

    /**
     * 志愿者提交申诉（Row 41 F「审核之后…有 7 天申诉期」）。
     *
     * <p><b>只有处罚可申诉</b>：P109 的申诉按钮只画在处罚卡片与处罚详情上，
     * 奖励卡片只有「查看详情」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(Long id, Long volunteerId, AppealSubmitDTO dto) {
        HonorRewardPunish rp = requireForUpdate(id);
        if (!rp.getVolunteerId().equals(volunteerId)) {
            // 不区分「不存在」与「不是你的」，避免拿这个接口枚举他人奖惩单
            throw new BusinessException("奖惩记录不存在");
        }
        if (!Integer.valueOf(HonorRewardPunish.TYPE_PUNISH).equals(rp.getType())) {
            throw new BusinessException("奖励记录无需申诉");
        }
        if (!Integer.valueOf(HonorRewardPunish.REVIEW_APPROVED).equals(rp.getReviewStatus())) {
            throw new BusinessException("该处罚尚未生效，无需申诉");
        }
        if (rp.getAppealDeadline() != null && LocalDateTime.now().isAfter(rp.getAppealDeadline())) {
            throw new BusinessException("申诉期已过（自处罚生效起 "
                    + honorProperties.getRewardPunish().getAppealDays() + " 天内可申诉）");
        }
        // CAS：只有「未申诉」的单能提交申诉。重复提交不该覆盖第一次的理由与时间——
        // 那会让受理人看到的是最后一次的说辞，而计时仍按第一次算。
        int rows = rewardPunishMapper.update(null, Wrappers.<HonorRewardPunish>lambdaUpdate()
                .set(HonorRewardPunish::getAppealStatus, HonorRewardPunish.APPEAL_PENDING)
                .set(HonorRewardPunish::getAppealReason, dto.getReason())
                .set(HonorRewardPunish::getAppealTime, LocalDateTime.now())
                .set(HonorRewardPunish::getUpdateTime, LocalDateTime.now())
                .eq(HonorRewardPunish::getId, id)
                .eq(HonorRewardPunish::getAppealStatus, HonorRewardPunish.APPEAL_NONE));
        if (rows != 1) {
            throw new BusinessException("该处罚已提交过申诉，请等待受理");
        }
    }

    /**
     * 受理申诉。
     *
     * <p><b>成立时的冲正走「反向流水」而不是改原始流水</b>：积分账本是追加型，
     * 原始那笔是「当时确实按这张单扣了分」的事实，改掉它等于抹掉历史。</p>
     *
     * <p><b>反向流水不能复用 {@code source_id}</b>：{@code uk_source(source_type, source_id)}
     * 已经被审核通过时那笔占住，再写一笔必然撞键。故反向流水走
     * {@code source_id = null + requestId}——{@code uk_request_id} 兜幂等，
     * 这也正是手工调整那条路径既有的机制。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(Long id, AppealHandleDTO dto, Long adminId) {
        HonorRewardPunish rp = requireForUpdate(id);
        boolean upheld = Boolean.TRUE.equals(dto.getUpheld());
        int target = upheld ? HonorRewardPunish.APPEAL_UPHELD : HonorRewardPunish.APPEAL_REJECTED;
        int rows = rewardPunishMapper.update(null, Wrappers.<HonorRewardPunish>lambdaUpdate()
                .set(HonorRewardPunish::getAppealStatus, target)
                .set(HonorRewardPunish::getAppealHandledBy, adminId)
                .set(HonorRewardPunish::getAppealHandleTime, LocalDateTime.now())
                .set(HonorRewardPunish::getAppealResult, dto.getResult())
                .set(HonorRewardPunish::getUpdateTime, LocalDateTime.now())
                .eq(HonorRewardPunish::getId, id)
                .eq(HonorRewardPunish::getAppealStatus, HonorRewardPunish.APPEAL_PENDING));
        if (rows != 1) {
            throw new BusinessException("该申诉不存在或已被受理");
        }
        // 受理结果提示。⚠️ 这一条是【推论、不是需求原文】：Row 41 F 只写了「审核之后会收到提示」，
        // 没写申诉有了结果要不要再提示一次。取这个口径的理由是——申诉是志愿者自己发起的，
        // 让他反复刷奖惩记录页面才知道结果，比不做提示更差。见 VolunteerNotification.TYPE_APPEAL_HANDLED。
        // 【成立与驳回都要发】只在成立时发，等于用「有没有收到提示」泄露结论，
        // 而驳回恰恰是更需要把理由送到他眼前的那一种。
        notificationService.notify(rp.getVolunteerId(),
                VolunteerNotification.TYPE_APPEAL_HANDLED,
                upheld ? "您的申诉已成立" : "您的申诉未获支持",
                appealResultContent(upheld, dto.getResult()),
                VolunteerNotification.BIZ_REWARD_PUNISH, id);
        if (!upheld) {
            return;
        }
        // 申诉成立：撤销处置 + 冲正积分。两件事都必须做，只做一件会留下
        // 「分退了但人还被限制着」或「限制解了但分没退」这种半截状态。
        sanctionService.liftBySource(VolunteerSanction.SOURCE_REWARD_PUNISH, id, adminId,
                "申诉成立，撤销处置");
        if (rp.getPointsDelta() != null && rp.getPointsDelta() != 0) {
            pointService.record(rp.getVolunteerId(), -rp.getPointsDelta(),
                    PointSourceType.REWARD_PUNISH, null, revertRequestId(id),
                    "申诉成立冲正：" + pointRemark(rp),
                    PointSourceType.OPERATOR_ADMIN, adminId);
        }
    }

    /** 受理结论必须原样带给志愿者——驳回时那句说明是他唯一能看到的理由。 */
    private static String appealResultContent(boolean upheld, String result) {
        String head = upheld
                ? "您的申诉已被受理并成立，相关处置已撤销、积分已冲正。"
                : "您的申诉已受理，经复核维持原处罚。";
        return result == null || result.isBlank() ? head : head + "受理说明：" + result;
    }

    /** 冲正流水的幂等键。与 {@code uk_request_id} 配套，重复受理不会冲正两次。 */
    static String revertRequestId(Long rewardPunishId) {
        return PointSourceType.REVERT_REQUEST_PREFIX + rewardPunishId;
    }

    private String pointRemark(HonorRewardPunish rp) {
        String label = Integer.valueOf(HonorRewardPunish.TYPE_REWARD).equals(rp.getType()) ? "奖励" : "处罚";
        return label + "：" + (rp.getCategory() == null ? "" : rp.getCategory())
                + (rp.getTitle() == null || rp.getTitle().isBlank() ? "" : "（" + rp.getTitle() + "）");
    }

    // ---------- ④ 查询 ----------

    /**
     * 我的奖惩记录——<b>只返回已通过审核的</b>（Row 41 F「审核才可显示」）。
     */
    public List<RewardPunishVO> myRecords(Long volunteerId) {
        List<HonorRewardPunish> rows = rewardPunishMapper.selectList(
                Wrappers.<HonorRewardPunish>lambdaQuery()
                        .eq(HonorRewardPunish::getVolunteerId, volunteerId)
                        .eq(HonorRewardPunish::getReviewStatus, HonorRewardPunish.REVIEW_APPROVED)
                        .orderByDesc(HonorRewardPunish::getId));
        return toVos(rows);
    }

    /** 后台列表：可按志愿者/类型/审核状态/申诉状态筛。 */
    public PageResult<RewardPunishVO> adminList(PageQuery query, Long volunteerId, Integer type,
                                                Integer reviewStatus, Integer appealStatus,
                                                boolean appealedOnly) {
        Page<HonorRewardPunish> page = query.toPage();
        rewardPunishMapper.selectPage(page, Wrappers.<HonorRewardPunish>lambdaQuery()
                .eq(volunteerId != null, HonorRewardPunish::getVolunteerId, volunteerId)
                .eq(type != null, HonorRewardPunish::getType, type)
                .eq(reviewStatus != null, HonorRewardPunish::getReviewStatus, reviewStatus)
                .eq(appealStatus != null, HonorRewardPunish::getAppealStatus, appealStatus)
                // 只有申诉受理权的人：把可见面收窄到【确实进入过申诉流程】的单子。
                // 上一轮为了让受理人不至于盲审，把列表放宽成「两权其一」，结果矫枉过正——
                // 只会受理申诉的人因此能翻遍全部奖惩单（含待审核的草稿、别人还没批的处罚）。
                // 「不盲审」要的是看到自己要判的那张单，不是看到全部。
                .ne(appealedOnly, HonorRewardPunish::getAppealStatus, HonorRewardPunish.APPEAL_NONE)
                .orderByDesc(HonorRewardPunish::getId));
        return PageResult.of(toVos(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    // 待审核条数用 adminList(reviewStatus=0) 的 PageResult.total，不另开 count 接口——
    // 同一个数字两套口径迟早对不上（DashboardVO 的既有决定也是这一条）。

    private HonorRewardPunish requireForUpdate(Long id) {
        HonorRewardPunish rp = rewardPunishMapper.selectByIdForUpdate(id);
        if (rp == null) {
            throw new BusinessException("奖惩记录不存在");
        }
        return rp;
    }

    private List<RewardPunishVO> toVos(List<HonorRewardPunish> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nameById = volunteerQueryService.listNamesByIds(
                rows.stream().map(HonorRewardPunish::getVolunteerId).distinct().toList());
        LocalDateTime now = LocalDateTime.now();
        return rows.stream().map(rp -> {
            RewardPunishVO vo = new RewardPunishVO();
            vo.setId(rp.getId());
            vo.setRpNo(rp.getRpNo());
            vo.setVolunteerId(rp.getVolunteerId());
            vo.setVolunteerName(nameById.get(rp.getVolunteerId()));
            vo.setType(rp.getType());
            vo.setCategory(rp.getCategory());
            vo.setTitle(rp.getTitle());
            vo.setDescription(rp.getDescription());
            vo.setPointsDelta(rp.getPointsDelta());
            vo.setActivityId(rp.getActivityId());
            vo.setSlotId(rp.getSlotId());
            vo.setViolationId(rp.getViolationId());
            vo.setSanctionScope(rp.getSanctionScope());
            vo.setSanctionScopeLabel(rp.getSanctionScope() == null
                    ? null : SanctionScope.labelOf(rp.getSanctionScope()));
            vo.setSanctionDays(rp.getSanctionDays());
            vo.setReviewStatus(rp.getReviewStatus());
            vo.setReviewTime(rp.getReviewTime());
            vo.setRejectReason(rp.getRejectReason());
            vo.setAppealStatus(rp.getAppealStatus());
            vo.setAppealDeadline(rp.getAppealDeadline());
            vo.setAppealReason(rp.getAppealReason());
            vo.setAppealResult(rp.getAppealResult());
            // 前端要据此决定「申诉」按钮显不显示。让服务端算：把「是处罚 + 已生效 + 未申诉 + 未过期」
            // 这四个条件散到前端去拼，迟早两端算出不同结果——按钮在但点了报错，或反过来。
            vo.setAppealable(Integer.valueOf(HonorRewardPunish.TYPE_PUNISH).equals(rp.getType())
                    && Integer.valueOf(HonorRewardPunish.REVIEW_APPROVED).equals(rp.getReviewStatus())
                    && Integer.valueOf(HonorRewardPunish.APPEAL_NONE).equals(rp.getAppealStatus())
                    && rp.getAppealDeadline() != null && now.isBefore(rp.getAppealDeadline()));
            vo.setCreateTime(rp.getCreateTime());
            return vo;
        }).toList();
    }
}
