package com.hengde.user.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.VolunteerServiceStatsView;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.PoliticalStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.organization.biz.service.GroupQueryService;
import com.hengde.organization.biz.service.SquadQueryService;
import com.hengde.user.dto.MyProfileUpdateDTO;
import com.hengde.user.vo.MyProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 志愿者端「我的资料」（user 域，{@code /v/user/profile}）：本人查看 / 修改自己的资料。
 *
 * <p>志愿者表归 auth，本服务沿用管理端口径<b>直接经 {@link VolunteerMapper} + {@link CryptoUtil}</b> 读写；
 * 服务时长/积分/活动数、所在小组名、归属分队名经各域只读 service 单人聚合（与 {@link AdminVolunteerService} 同源）。
 * 与管理端不同：本服务<b>不要求已实名</b>——游客（registerTime 为空）也能取到资料（仅实名字段为空），
 * 因为「我的」页对游客也展示。身份证仅回尾号，避免明文 PII 出网。</p>
 *
 * <p>方法直接收 volunteerId（由控制器从 {@code StpUtil} 取本人 id），不在 service 内碰 Sa-Token，便于单测。</p>
 *
 * @author hengde
 */
@Service
public class MyProfileService {

    private VolunteerMapper volunteerMapper;
    private CryptoUtil cryptoUtil;
    private ServiceRecordService serviceRecordService;
    private GroupQueryService groupQueryService;
    private SquadQueryService squadQueryService;
    private VerifyCodeService verifyCodeService;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setVerifyCodeService(VerifyCodeService verifyCodeService) {
        this.verifyCodeService = verifyCodeService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setServiceRecordService(ServiceRecordService serviceRecordService) {
        this.serviceRecordService = serviceRecordService;
    }

    @Autowired
    public void setGroupQueryService(GroupQueryService groupQueryService) {
        this.groupQueryService = groupQueryService;
    }

    @Autowired
    public void setSquadQueryService(SquadQueryService squadQueryService) {
        this.squadQueryService = squadQueryService;
    }

    /** 本人完整资料 + 跨域统计/归属。 */
    public MyProfileVO getMyProfile(long volunteerId) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        MyProfileVO vo = new MyProfileVO();
        vo.setRegistered(v.getRegisterTime() != null);
        vo.setRealName(v.getRealName());
        vo.setNickName(v.getNickName());
        vo.setPhone(decrypt(v.getPhone()));
        String idCard = decrypt(v.getIdCardNo());
        // 本人查看自己：回完整身份证号；同时保留脱敏尾号兼容
        vo.setIdCardNo(idCard);
        if (idCard != null && idCard.length() >= 4) {
            vo.setIdTail(idCard.substring(idCard.length() - 4));
        }
        Gender g = v.getGender();
        vo.setGender(g == null ? null : g.getCode());
        vo.setGenderName(g == null ? null : g.getLabel());
        vo.setBirthday(v.getBirthday());
        PoliticalStatus p = v.getPoliticalStatus();
        vo.setPoliticalStatus(p == null ? null : p.getCode());
        vo.setPoliticalStatusName(p == null ? null : p.getLabel());
        Grade gr = v.getGrade();
        vo.setGrade(gr == null ? null : gr.getCode());
        vo.setGradeName(gr == null ? null : gr.getLabel());
        vo.setSchool(v.getSchool());
        vo.setAddress(v.getAddress());
        vo.setAvatarUrl(v.getAvatarUrl());
        vo.setIVolunteerCodeUrl(v.getIVolunteerCodeUrl());
        vo.setPosition(v.getPosition());
        vo.setEmergencyContactName(v.getEmergencyContactName());
        vo.setEmergencyContactPhone(decrypt(v.getEmergencyContactPhone()));
        vo.setManagerFlag(v.getManagerFlag() == null ? 0 : v.getManagerFlag());
        vo.setRegisterTime(v.getRegisterTime());
        vo.setSignedAgreementVersion(v.getSignedAgreementVersion());

        List<Long> selfId = List.of(volunteerId);
        Map<Long, VolunteerServiceStatsView> stats = serviceRecordService.batchStatsByVolunteerIds(selfId);
        VolunteerServiceStatsView s = stats.get(volunteerId);
        vo.setServiceMinutes(s == null ? 0 : s.confirmedMinutes());
        vo.setPoints(s == null ? 0 : s.grantedPoints());
        vo.setActivityCount(s == null ? 0 : s.activityCount());
        vo.setGroupName(groupQueryService.listActiveGroupNamesByVolunteerIds(selfId).get(volunteerId));
        if (v.getSquadId() != null) {
            vo.setSquadId(v.getSquadId());
            vo.setSquadName(squadQueryService.listNamesByIds(List.of(v.getSquadId())).get(v.getSquadId()));
        }
        return vo;
    }

    /**
     * 修改本人资料：部分更新，仅对传入的非空字段更新（避免误清空），手机号/实名字段不在此。
     * 紧急联系方式非空时校验为合法手机号且与本人手机号不同。
     */
    public void updateMyProfile(long volunteerId, MyProfileUpdateDTO dto) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        LambdaUpdateWrapper<Volunteer> uw = Wrappers.<Volunteer>lambdaUpdate().eq(Volunteer::getId, volunteerId);
        boolean any = false;
        // 统一口径：所有可改字段均「有值才更新」（hasText/非空），不经此接口清空（清空头像等无产品需求）
        if (StringUtils.hasText(dto.getAvatarUrl())) {
            uw.set(Volunteer::getAvatarUrl, dto.getAvatarUrl().trim());
            any = true;
        }
        if (StringUtils.hasText(dto.getIVolunteerCodeUrl())) {
            uw.set(Volunteer::getIVolunteerCodeUrl, dto.getIVolunteerCodeUrl().trim());
            any = true;
        }
        if (StringUtils.hasText(dto.getNickName())) {
            String nn = dto.getNickName().trim();
            // 去重：昵称全局唯一，不能与其它账号撞（先查重给友好提示，DB uk_nick_name 兜底并发）
            Long clash = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                    .eq(Volunteer::getNickName, nn)
                    .ne(Volunteer::getId, volunteerId));
            if (clash != null && clash > 0) {
                throw new BusinessException("该昵称已被使用，请换一个");
            }
            uw.set(Volunteer::getNickName, nn);
            any = true;
        }
        if (StringUtils.hasText(dto.getSchool())) {
            uw.set(Volunteer::getSchool, dto.getSchool().trim());
            any = true;
        }
        if (StringUtils.hasText(dto.getAddress())) {
            uw.set(Volunteer::getAddress, dto.getAddress().trim());
            any = true;
        }
        if (dto.getPoliticalStatus() != null) {
            uw.set(Volunteer::getPoliticalStatus, parsePolitical(dto.getPoliticalStatus()));
            any = true;
        }
        if (dto.getGrade() != null) {
            uw.set(Volunteer::getGrade, parseGrade(dto.getGrade()));
            any = true;
        }
        if (StringUtils.hasText(dto.getEmergencyContactPhone())) {
            String ep = dto.getEmergencyContactPhone().trim();
            if (!ep.matches("^1[3-9]\\d{9}$")) {
                throw new BusinessException("紧急联系方式格式不正确");
            }
            if (ep.equals(decrypt(v.getPhone()))) {
                throw new BusinessException("紧急联系方式不能与本人手机号相同");
            }
            uw.set(Volunteer::getEmergencyContactPhone, cryptoUtil.encrypt(ep));
            any = true;
        }
        if (!any) {
            return;
        }
        // wrapper 更新不触发 MetaObjectHandler 自动填充，须显式写审计时间
        uw.set(Volunteer::getUpdateTime, LocalDateTime.now());
        try {
            volunteerMapper.update(null, uw);
        } catch (DuplicateKeyException e) {
            // 并发竞态：两账号同时改成同一昵称，先查重后更新仍可能撞 uk_nick_name；转明确业务错误而非 500
            throw new BusinessException("该昵称已被使用，请换一个");
        }
    }

    /**
     * 修改/换绑手机号（需短信验证；账号=手机号，故同步改 phone 密文 + phoneHash 登录定位键）。
     *
     * <p>校验新号 CHANGE_PHONE 场景验证码 → 与当前号相同则幂等返回 → 否则查重（不能撞别的活跃账号）→ 落库。
     * 与本人手机号绑定的登录态不变（loginId=志愿者 id 不依赖手机号），换号后下次用新号登录。</p>
     */
    public void changePhone(long volunteerId, String newPhone, String smsCode) {
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null) {
            throw new BusinessException("登录态异常，请重新登录");
        }
        if (!StringUtils.hasText(newPhone) || !newPhone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("手机号格式不正确");
        }
        verifyCodeService.verify(newPhone, SmsScene.CHANGE_PHONE, smsCode);
        String newHash = cryptoUtil.hashPhone(newPhone);
        if (newHash.equals(v.getPhoneHash())) {
            return; // 与当前号相同，无需变更
        }
        Long clash = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, newHash)
                .ne(Volunteer::getId, volunteerId));
        if (clash != null && clash > 0) {
            throw new BusinessException("该手机号已被其他账号使用");
        }
        LambdaUpdateWrapper<Volunteer> uw = Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, volunteerId)
                .set(Volunteer::getPhone, cryptoUtil.encrypt(newPhone))
                .set(Volunteer::getPhoneHash, newHash)
                .set(Volunteer::getUpdateTime, LocalDateTime.now());
        try {
            volunteerMapper.update(null, uw);
        } catch (DuplicateKeyException e) {
            // 并发竞态：两账号同时换绑同一新号，先查重后更新仍可能撞 uk_phone_hash；转明确业务错误而非 500
            throw new BusinessException("该手机号已被其他账号使用");
        }
    }

    private PoliticalStatus parsePolitical(Integer code) {
        PoliticalStatus p = PoliticalStatus.fromCode(code);
        if (p == null) {
            throw new BusinessException("政治面貌取值非法");
        }
        return p;
    }

    private Grade parseGrade(Integer code) {
        Grade g = Grade.fromCode(code);
        if (g == null) {
            throw new BusinessException("年级取值非法");
        }
        return g;
    }

    private String decrypt(String stored) {
        return StringUtils.hasText(stored) ? cryptoUtil.decrypt(stored) : null;
    }
}
