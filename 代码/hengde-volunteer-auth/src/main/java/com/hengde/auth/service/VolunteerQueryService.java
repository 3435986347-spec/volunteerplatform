package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.vo.VolunteerBackfillView;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.auth.vo.VolunteerProfileView;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
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

    /**
     * 批量取志愿者姓名（id → realName），仅 {@code select} 姓名列、<b>不解密手机号</b>。
     *
     * <p>供公开展示场景（如活动留言列表）只取姓名用，避免 {@link #listDisplayByIds} 把明文手机号
     * 解密带到调用方内存。无姓名（游客未实名）的不入 Map。</p>
     *
     * @param volunteerIds 志愿者 id 集合
     * @return id -> 姓名；空集合返回空 Map
     */
    public Map<Long, String> listNamesByIds(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Map.of();
        }
        List<Volunteer> list = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .select(Volunteer::getId, Volunteer::getRealName)
                .in(Volunteer::getId, volunteerIds));
        return list.stream()
                .filter(v -> v.getRealName() != null)
                .collect(Collectors.toMap(Volunteer::getId, Volunteer::getRealName));
    }

    /**
     * 志愿者账号是否处于可用状态。供志愿者端 RBAC 鉴权：停用/注销/不存在的志愿者不应携带任何权限点
     * （即便 token 未过期，杜绝「停用但 token 仍在」越权窗口）。
     *
     * @param volunteerId 志愿者 id
     * @return true=存在且未被禁用/注销；status 为 null 按正常处理（与 DB 默认 0 对齐）
     */
    public boolean isActive(Long volunteerId) {
        if (volunteerId == null) {
            return false;
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return false;
        }
        Integer status = v.getStatus();
        return status == null || UserStatus.NORMAL.equals(status);
    }

    /**
     * 志愿者是否「活跃 + 已标记管理团队」（一次查库）。供志愿者端 RBAC <b>读取路径</b>用——
     * 取消 manager_flag（降级）后立即失效，与授权写入门槛 {@code manager_flag=1} 一致，避免 stale 授权继续生效。
     *
     * @param volunteerId 志愿者 id
     * @return true=存在、未停用且 {@code manager_flag=1}
     */
    public boolean isActiveManager(Long volunteerId) {
        if (volunteerId == null) {
            return false;
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return false;
        }
        Integer status = v.getStatus();
        boolean active = status == null || UserStatus.NORMAL.equals(status);
        return active && Integer.valueOf(1).equals(v.getManagerFlag());
    }

    /**
     * 志愿者标记/授权页基础信息（按 id 定位）：姓名 + 管理团队标记 + 是否已实名。
     *
     * @param volunteerId 志愿者 id
     * @return 信息视图；志愿者不存在返回 null
     */
    public VolunteerFlagInfoView getFlagInfo(Long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            return null;
        }
        return new VolunteerFlagInfoView(v.getId(), v.getRealName(),
                v.getManagerFlag() == null ? 0 : v.getManagerFlag(), v.getRegisterTime() != null);
    }

    /**
     * 该志愿者是否被标记为「管理团队」（V11 manager_flag）。供 activity 积分发放判定 ×1.2 倍率。
     *
     * @param volunteerId 志愿者 id
     * @return true=管理团队成员；志愿者不存在或未标记返回 false
     */
    public boolean isManager(Long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        return v != null && Integer.valueOf(1).equals(v.getManagerFlag());
    }

    /**
     * 活动补录定位志愿者：按身份证或手机号<b>精确匹配</b>唯一志愿者（身份证优先），姓名仅作交叉校验。
     *
     * <p>身份证/手机号经 {@link CryptoUtil#hashIdCard}/{@link CryptoUtil#hashPhone} 算哈希查询，不解密。
     * 必须提供身份证或手机号之一（避免重名歧义）；命中多人或姓名不符直接拒；无匹配返回 null。</p>
     *
     * @param idCard 身份证号（明文，可空）
     * @param phone  手机号（明文，可空）
     * @param name   姓名（可空，提供则须与命中人一致）
     * @return 匹配到的志愿者定位视图；无匹配返回 null
     */
    public VolunteerBackfillView findForBackfill(String idCard, String phone, String name) {
        boolean hasIdCard = StringUtils.hasText(idCard);
        boolean hasPhone = StringUtils.hasText(phone);
        if (!hasIdCard && !hasPhone) {
            throw new BusinessException("请提供手机号或身份证以精确匹配志愿者");
        }
        Volunteer byIdCard = hasIdCard ? selectSingleByHash(true, cryptoUtil.hashIdCard(idCard)) : null;
        Volunteer byPhone = hasPhone ? selectSingleByHash(false, cryptoUtil.hashPhone(phone)) : null;

        Volunteer v;
        if (hasIdCard && hasPhone) {
            // 两者都传：须命中同一志愿者，否则说明录入冲突（身份证属 A、手机号属 B）——直接拒，避免误补
            if (byIdCard == null && byPhone == null) {
                return null;
            }
            if (byIdCard == null || byPhone == null || !byIdCard.getId().equals(byPhone.getId())) {
                throw new BusinessException("身份证与手机号未匹配到同一志愿者，请核对");
            }
            v = byIdCard;
        } else {
            v = hasIdCard ? byIdCard : byPhone;
            if (v == null) {
                return null;
            }
        }
        if (StringUtils.hasText(name) && !name.trim().equals(v.getRealName())) {
            throw new BusinessException("姓名与手机号/身份证不匹配");
        }
        return new VolunteerBackfillView(v.getId(), v.getRealName(), v.getSchool());
    }

    /** 按身份证/手机号哈希取唯一志愿者；命中多人抛异常，无命中返回 null。 */
    private Volunteer selectSingleByHash(boolean byIdCard, String hash) {
        var wrapper = Wrappers.<Volunteer>lambdaQuery();
        if (byIdCard) {
            wrapper.eq(Volunteer::getIdCardHash, hash);
        } else {
            wrapper.eq(Volunteer::getPhoneHash, hash);
        }
        List<Volunteer> list = volunteerMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            throw new BusinessException("命中多名志愿者，请用更精确的条件");
        }
        return list.get(0);
    }

    private VolunteerDisplayView toDisplay(Volunteer v) {
        Integer genderCode = v.getGender() == null ? Gender.UNKNOWN.getCode() : v.getGender().getCode();
        Integer gradeCode = v.getGrade() == null ? null : v.getGrade().getCode();
        String phone = StringUtils.hasText(v.getPhone()) ? cryptoUtil.decrypt(v.getPhone()) : null;
        return new VolunteerDisplayView(v.getId(), v.getRealName(), v.getSchool(), gradeCode, genderCode, phone);
    }
}
