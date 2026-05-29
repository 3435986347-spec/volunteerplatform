package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.common.constant.Gender;
import com.hengde.common.crypto.CryptoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 志愿者只读查询服务：供其他领域（如 activity 报名资格校验/管理端报名列表）跨模块取志愿者档案。
 *
 * <p>按 CLAUDE.md「领域间通过对方 service 接口调用」约定暴露，避免外部模块直接捅
 * {@link VolunteerMapper}；并以收敛视图（{@link VolunteerProfileView}/{@link VolunteerDisplayView}）出参，
 * 不外泄整个加密实体。手机号解密在本侧（持有 {@link CryptoUtil}）完成。</p>
 *
 * @author hengde
 */
@Service
public class VolunteerQueryService {

    private VolunteerMapper volunteerMapper;
    private CryptoUtil cryptoUtil;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    /**
     * 取志愿者资格档案。
     *
     * @param volunteerId 志愿者 id
     * @return 资格视图；志愿者不存在时返回 null（由调用方决定如何处理）
     */
    public VolunteerProfileView getProfileForEligibility(Long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return null;
        }
        Integer genderCode = v.getGender() == null ? Gender.UNKNOWN.getCode() : v.getGender().getCode();
        Integer gradeCode = v.getGrade() == null ? null : v.getGrade().getCode();
        return new VolunteerProfileView(v.getId(), genderCode, v.getBirthday(), gradeCode, v.getStatus());
    }

    /**
     * 批量取志愿者展示信息（姓名/学校/年级/性别/手机号明文），按 id 映射返回，供管理端报名列表/导出 join。
     *
     * @param volunteerIds 志愿者 id 集合
     * @return id -> 展示视图；空集合返回空 Map
     */
    public Map<Long, VolunteerDisplayView> listDisplayByIds(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> list = volunteerMapper.selectList(
                Wrappers.<Volunteer>lambdaQuery().in(Volunteer::getId, volunteerIds));
        return list.stream().map(this::toDisplay)
                .collect(Collectors.toMap(VolunteerDisplayView::id, Function.identity()));
    }

    private VolunteerDisplayView toDisplay(Volunteer v) {
        Integer genderCode = v.getGender() == null ? Gender.UNKNOWN.getCode() : v.getGender().getCode();
        Integer gradeCode = v.getGrade() == null ? null : v.getGrade().getCode();
        String phone = StringUtils.hasText(v.getPhone()) ? cryptoUtil.decrypt(v.getPhone()) : null;
        return new VolunteerDisplayView(v.getId(), v.getRealName(), v.getSchool(), gradeCode, genderCode, phone);
    }
}
