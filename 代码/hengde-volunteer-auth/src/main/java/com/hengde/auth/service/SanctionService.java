package com.hengde.auth.service;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dao.VolunteerSanctionMapper;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 处置措施的写入口。由 honor 的奖惩审核调用，不直接对外暴露 HTTP。
 *
 * <p>读走 {@link SanctionQueryService}；写读分开与 {@code VolunteerQueryService} /
 * {@code VolunteerAdminService} 的既有约定一致。</p>
 *
 * @author hengde
 */
@Service
public class SanctionService {

    private VolunteerSanctionMapper sanctionMapper;
    private VolunteerMapper volunteerMapper;

    @Autowired
    public void setSanctionMapper(VolunteerSanctionMapper sanctionMapper) {
        this.sanctionMapper = sanctionMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    /**
     * 施加一条处置。
     *
     * @param volunteerId 志愿者
     * @param sourceType  来源类型，见 {@link VolunteerSanction#SOURCE_REWARD_PUNISH}
     * @param sourceId    来源单据 id
     * @param scope       能力域，见 {@link SanctionScope}
     * @param days        限制天数；{@code null} = 不设期限（如「拒绝其使用本程序」）；
     *                    否则须落在 {@code 1 ~ }{@link VolunteerSanction#MAX_SANCTION_DAYS}
     * @return 处置 id
     * @throws BusinessException 志愿者为空/已不存在、能力域未知、或天数越界
     */
    @Transactional(rollbackFor = Exception.class)
    public Long impose(Long volunteerId, int sourceType, Long sourceId, Integer scope, Integer days) {
        if (volunteerId == null) {
            throw new BusinessException("处置必须指定志愿者");
        }
        // 未知能力域直接拒——写进去就是一条【永远不会被任何闸门认出】的处置：
        // 后台看着已处罚、志愿者却畅通无阻，而且没有任何报错会提示这件事。
        SanctionScope.assertKnown(scope);
        // 【上下限都在这一层兜底，不能只靠 RewardPunishService】那是目前唯一的调用方，
        // 但不变量属于「写处置」这个动作本身：任何绕过它的路径（内部调用、数据修复、
        // 下一批新加的入口）都不该能落进一个 plusDays 会抛 DateTimeException 的值。
        // 上限的含义见 VolunteerSanction.MAX_SANCTION_DAYS，V33 的 CHECK 是第三道。
        if (days != null && (days <= 0 || days > VolunteerSanction.MAX_SANCTION_DAYS)) {
            throw new BusinessException("限制天数须在 1~" + VolunteerSanction.MAX_SANCTION_DAYS
                    + " 之间；不设期限请不传天数");
        }
        // 【锁住志愿者这一行】它是「处置」的串行化父行，报名/签到那几道闸门读处置前会取同一行的 S 锁。
        // 不锁的话，处置写入与闸门查询分处两个互不相干的事务，中间那段
        // 「闸门查无处罚 → 本方法提交处罚 → 报名提交」的窗口没有任何东西挡着，
        // 刚生效的处罚会被那一次报名整个漏过去。锁的完整论证见 VolunteerMapper.selectByIdForUpdate。
        //
        // 取 X 而不是 S：本事务随后要 INSERT 这个志愿者的处置行，而调用方（奖惩审核）
        // 在此之前已经用 getGrantEligibilityForUpdate 取过同一行的 X——两处都取 X 才不产生锁升级。
        if (volunteerMapper.selectByIdForUpdate(volunteerId) == null) {
            // 走到这里说明志愿者行已不存在（含逻辑删除）。调用方本应先复核过；
            // 真出现就是并发删除，宁可让整笔审核回滚，也不要留下一条无主处置。
            throw new BusinessException("该志愿者已不存在，无法施加处置");
        }
        // 【必须向下取整到秒】MySQL 的 DATETIME（无小数位）在写入时是【四舍五入】而不是截断：
        // 12:00:00.700 会被存成 12:00:01。于是 effective_time 落在未来最多 0.5 秒，
        // 而闸门的条件是 effective_time <= NOW()——这半秒里处置【明明已经施加却不生效】。
        // 窗口很短，但它会让「审核通过后立刻报名」偶尔漏过去，且因为依赖当时的毫秒数而无法稳定复现。
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        VolunteerSanction s = new VolunteerSanction();
        s.setVolunteerId(volunteerId);
        s.setSourceType(sourceType);
        s.setSourceId(sourceId);
        s.setScope(scope);
        s.setEffectiveTime(now);
        // 到期时刻【落库而不是每次由天数现算】：口径一旦落地就固定下来，
        // 事后改天数配置不会追溯性地延长或缩短已在执行的处罚。
        s.setExpireTime(days == null ? null : now.plusDays(days));
        s.setStatus(VolunteerSanction.STATUS_ACTIVE);
        sanctionMapper.insert(s);
        return s.getId();
    }

    /**
     * 解除某张单据产生的全部生效中处置（申诉成立、管理员撤销）。
     *
     * @return 实际解除的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int liftBySource(int sourceType, Long sourceId, Long operatorId, String reason) {
        if (sourceId == null) {
            return 0;
        }
        return sanctionMapper.liftBySource(sourceType, sourceId, operatorId, reason);
    }
}
