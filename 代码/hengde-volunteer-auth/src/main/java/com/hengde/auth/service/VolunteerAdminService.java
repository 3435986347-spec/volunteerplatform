package com.hengde.auth.service;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
     * 设置/取消志愿者「管理团队」标记（V11 {@code manager_flag}）。
     *
     * <p>该标记影响活动积分倍率（管理团队 ×1.2），故只允许标记<b>已实名志愿者</b>——
     * 游客（{@code registerTime} 为 null）不参与活动计分，标记无意义。幂等：重复设同值直接返回。</p>
     *
     * @param volunteerId 志愿者 id
     * @param flag        目标标记，非 1 一律按 0（取消）处理
     */
    public void setManagerFlag(Long volunteerId, Integer flag) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("志愿者不存在");
        }
        if (v.getRegisterTime() == null) {
            throw new BusinessException("仅已实名志愿者可标记为管理团队");
        }
        int target = Integer.valueOf(FLAG_ON).equals(flag) ? FLAG_ON : FLAG_OFF;
        if (Integer.valueOf(target).equals(v.getManagerFlag())) {
            return;
        }
        Volunteer update = new Volunteer();
        update.setId(volunteerId);
        update.setManagerFlag(target);
        volunteerMapper.updateById(update);
    }
}
