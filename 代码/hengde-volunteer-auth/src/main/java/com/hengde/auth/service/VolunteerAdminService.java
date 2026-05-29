package com.hengde.auth.service;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 志愿者管理端写操作服务：供管理后台对志愿者做受控修改。
 *
 * <p>与只读的 {@link VolunteerQueryService} 分开，保持后者「纯查询」契约不被写方法污染。
 * auth 持有 {@link Volunteer} 表的写权（PII 加密、字段语义都在本域），其他域只经服务接口触达，
 * 不直接捅 {@link VolunteerMapper}。鉴权由调用方（organization 控制器 {@code @SaCheckPermission}）负责。</p>
 *
 * @author hengde
 */
@Service
public class VolunteerAdminService {

    /** 管理团队标记：0=否，1=是 */
    private static final int FLAG_OFF = 0;
    private static final int FLAG_ON = 1;

    private VolunteerMapper volunteerMapper;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    /**
     * 设置/取消志愿者「管理团队」标记（V11 {@code manager_flag}），并记录操作人（V13 审计）。
     *
     * <p>本服务是跨模块写边界，不依赖上游 DTO 兜底：{@code flag} 必须是 0 或 1，非法（null/2/-1…）直接抛业务异常，
     * 不静默当取消处理。该标记影响活动积分倍率（管理团队 ×1.2），故<b>仅「设为 1」要求已实名志愿者</b>；
     * <b>取消（0）不限</b>——允许清理历史/误标在游客上的脏标记。幂等：当前值已等于目标值时直接返回，不写库、不记审计。</p>
     *
     * @param volunteerId 志愿者 id
     * @param flag        目标标记，仅允许 0（取消）/1（设为管理团队）
     * @param operatorId  操作人 admin_user.id（落审计列）
     */
    public void setManagerFlag(Long volunteerId, Integer flag, Long operatorId) {
        int target = requireValidFlag(flag);
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("志愿者不存在");
        }
        int current = v.getManagerFlag() == null ? FLAG_OFF : v.getManagerFlag();
        if (current == target) {
            return;
        }
        if (target == FLAG_ON && v.getRegisterTime() == null) {
            throw new BusinessException("仅已实名志愿者可标记为管理团队");
        }
        Volunteer update = new Volunteer();
        update.setId(volunteerId);
        update.setManagerFlag(target);
        update.setManagerFlagBy(operatorId);
        update.setManagerFlagTime(LocalDateTime.now());
        volunteerMapper.updateById(update);
    }

    private int requireValidFlag(Integer flag) {
        if (flag == null || (flag != FLAG_OFF && flag != FLAG_ON)) {
            throw new BusinessException("管理团队标记值只能为 0 或 1");
        }
        return flag;
    }
}
