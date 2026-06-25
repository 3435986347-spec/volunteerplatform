package com.hengde.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.VolunteerServiceStatsView;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.PoliticalStatus;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageResult;
import com.hengde.organization.biz.service.GroupQueryService;
import com.hengde.organization.biz.service.SquadQueryService;
import com.hengde.user.dto.VolunteerQueryDTO;
import com.hengde.user.dto.VolunteerUpdateDTO;
import com.hengde.user.vo.AdminVolunteerDetailVO;
import com.hengde.user.vo.AdminVolunteerListVO;
import com.hengde.user.vo.VolunteerExportRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 志愿者管理（user 域）：列表/详情/修改/停用·恢复/删除/导出/重置密码。
 *
 * <p>志愿者表归 auth 域，本服务按计划<b>直接经 {@link VolunteerMapper} + {@link CryptoUtil}</b> 读写
 * （列表多条件筛选与全量改字段超出 auth 只读视图能表达的范围）；「管理团队」标记与权限仍由 organization 域负责，
 * 本服务不触碰。跨域展示字段（归属分队名/所在小组名、服务时长/积分/参与活动数）经各域只读 service 批量聚合，
 * 列表为 best-effort（无记录显 0），详情按单人精确。</p>
 *
 * <p><b>口径</b>：列表/导出仅含已实名志愿者（{@code register_time} 非空）；<b>按 id 的管理操作</b>
 * （详情/修改/状态/删除/重置）统一经 {@link #requireRegisteredVolunteer} 收敛——知道游客 id 也无法读取/改/停/删游客行；
 * 注销态(status=2)仍属已实名，照常处理。逻辑删除经 {@code BaseEntity} 的 {@code @TableLogic} 自动过滤。</p>
 *
 * <p><b>鉴权</b>：控制器按 {@code user:*} 权限点拦截；<b>修改（user:edit）写死仅超管</b>，由本服务
 * {@link #requireSuperAdmin} 手写校验（user:edit 不在权限点表，与 organization 同口径）。</p>
 *
 * @author hengde
 */
@Service
public class AdminVolunteerService {

    private VolunteerMapper volunteerMapper;
    private AdminUserMapper adminUserMapper;
    private CryptoUtil cryptoUtil;
    private ServiceRecordService serviceRecordService;
    private GroupQueryService groupQueryService;
    private SquadQueryService squadQueryService;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
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

    // ---------- 查询 ----------

    /** 志愿者列表：多条件筛选 + 分页，跨域 best-effort 聚合服务时长/积分/参与活动/归属。 */
    public PageResult<AdminVolunteerListVO> list(VolunteerQueryDTO dto) {
        Page<Volunteer> page = dto.toPage();
        volunteerMapper.selectPage(page, buildWrapper(dto));
        List<Volunteer> records = page.getRecords();
        Enrichment enrich = enrichmentFor(records);
        List<AdminVolunteerListVO> vos = records.stream().map(v -> {
            AdminVolunteerListVO vo = new AdminVolunteerListVO();
            fillCommon(vo, v, enrich);
            return vo;
        }).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 志愿者详情：列表字段 + 身份证尾号/紧急联系人/地址等，单人精确聚合。 */
    public AdminVolunteerDetailVO detail(Long id) {
        Volunteer v = requireRegisteredVolunteer(id);
        Enrichment enrich = enrichmentFor(List.of(v));
        AdminVolunteerDetailVO vo = new AdminVolunteerDetailVO();
        fillCommon(vo, v, enrich);
        String idCard = decrypt(v.getIdCardNo());
        if (idCard != null && idCard.length() >= 4) {
            vo.setIdTail(idCard.substring(idCard.length() - 4));
        }
        vo.setBirthday(v.getBirthday());
        vo.setAddress(v.getAddress());
        vo.setEmergencyContactName(v.getEmergencyContactName());
        String emergencyPhone = decrypt(v.getEmergencyContactPhone());
        vo.setEmergencyContactPhone(emergencyPhone);
        vo.setEmergency(joinEmergency(v.getEmergencyContactName(), emergencyPhone));
        vo.setSignedAgreementVersion(v.getSignedAgreementVersion());
        return vo;
    }

    // ---------- 写操作 ----------

    /** 全量修改可编辑资料（操作人取当前管理端登录态）。 */
    public void update(Long id, VolunteerUpdateDTO dto) {
        updateBy(id, dto, StpAdminUtil.getLoginIdAsLong());
    }

    /**
     * 全量修改可编辑资料（显式操作人，供测试 / 绕过 Sa-Token 上下文）。<b>仅超管</b>（user:edit 写死仅超管）。
     *
     * <p>全量 PUT 语义：可清空字段（school/squadId/紧急联系人/性别/政治面貌/年级，传 null 即清空）。
     * 手机号变更则重算密文+phoneHash 并查重；手机号/紧急联系电话留空（null/空串）= 清空对应密文与 hash。
     * 不改 managerFlag/权限/status。</p>
     */
    public void updateBy(Long id, VolunteerUpdateDTO dto, Long operatorAdminId) {
        requireSuperAdmin(operatorAdminId);
        Volunteer v = requireRegisteredVolunteer(id);
        LambdaUpdateWrapper<Volunteer> uw = Wrappers.<Volunteer>lambdaUpdate().eq(Volunteer::getId, id);
        uw.set(Volunteer::getRealName, dto.getRealName());
        uw.set(Volunteer::getGender, parseGender(dto.getGender()));
        uw.set(Volunteer::getPoliticalStatus, parsePolitical(dto.getPolitical()));
        uw.set(Volunteer::getGrade, parseGrade(dto.getGrade()));
        uw.set(Volunteer::getSchool, trimToNull(dto.getSchool()));
        uw.set(Volunteer::getSquadId, dto.getSquadId());
        uw.set(Volunteer::getEmergencyContactName, trimToNull(dto.getEmergencyContactName()));
        String emergencyPhone = trimToNull(dto.getEmergencyContactPhone());
        uw.set(Volunteer::getEmergencyContactPhone, emergencyPhone == null ? null : cryptoUtil.encrypt(emergencyPhone));
        applyPhone(uw, v, trimToNull(dto.getPhone()), id);
        // wrapper 更新不触发 MetaObjectHandler 自动填充，须显式写审计时间（与 CAS 更新口径一致）
        uw.set(Volunteer::getUpdateTime, LocalDateTime.now());
        volunteerMapper.update(null, uw);
    }

    /** 暂停/恢复账号：仅 0正常 / 1禁用，注销态不经此设置。 */
    public void setStatus(Long id, Integer status) {
        if (status == null || (!UserStatus.NORMAL.equals(status) && !UserStatus.BANNED.equals(status))) {
            throw new BusinessException("状态只能为 0（正常）或 1（禁用）");
        }
        requireRegisteredVolunteer(id);
        Volunteer update = new Volunteer();
        update.setId(id);
        update.setStatus(status);
        volunteerMapper.updateById(update);
    }

    /**
     * 删除志愿者：逻辑删除 + <b>释放唯一字段并清空凭据/敏感 PII</b>，使该手机号/身份证之后可重新注册。
     *
     * <p>与「停用」({@link #setStatus} 置 BANNED) 的语义区别（产品决策）：
     * <ul>
     *   <li><b>删除</b>=账号内容清空、放开手机号——openid 改写为 {@code deleted:{id}} 占位（满足 NOT NULL UNIQUE）、
     *       phone_hash/id_card_hash 置 null（放开 {@code uk_phone_hash} 与身份证查重）、phone/idCardNo/password/
     *       紧急联系电话密文清空；行保留为墓碑（is_deleted=1）以维持服务记录等引用。之后同手机号可重新走验证码登录+实名注册。</li>
     *   <li><b>停用</b>=账号与唯一字段原样保留，凭「手机号已绑定」拦截重复注册、并在登录时按 status 拒绝。</li>
     * </ul>
     * 同一事务内先释放字段再逻辑删除，避免遗留唯一键导致后续注册撞 {@code uk_phone_hash} 报 500。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireRegisteredVolunteer(id);
        LambdaUpdateWrapper<Volunteer> uw = Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, id)
                .set(Volunteer::getOpenid, "deleted:" + id)   // 释放 openid 唯一键（不与 p:/wx/dev 前缀冲突）
                .set(Volunteer::getUnionid, null)
                .set(Volunteer::getPhone, null)
                .set(Volunteer::getPhoneHash, null)            // 释放手机号唯一键 → 可重新注册
                .set(Volunteer::getIdCardNo, null)
                .set(Volunteer::getIdCardHash, null)           // 释放身份证查重
                .set(Volunteer::getPassword, null)
                .set(Volunteer::getEmergencyContactPhone, null)
                .set(Volunteer::getUpdateTime, LocalDateTime.now());
        volunteerMapper.update(null, uw);
        volunteerMapper.deleteById(id);
    }

    /**
     * 重置密码：<b>清空</b>该志愿者的登录密码（password 置 null）。
     *
     * <p>口径（产品决策，安全优先）：管理员不设定、不知晓任何明文密码——重置即「清空」，志愿者之后用
     * 「手机号+验证码」登录、再在安全中心自设新密码（也可自助走「忘记密码」短信重置）。V20 起 volunteer 表已有
     * password 列，此操作不再是 no-op。已未设密码者重置为幂等空操作。</p>
     */
    public void resetPassword(Long id) {
        requireRegisteredVolunteer(id);
        LambdaUpdateWrapper<Volunteer> uw = Wrappers.<Volunteer>lambdaUpdate()
                .eq(Volunteer::getId, id)
                .set(Volunteer::getPassword, null)
                // wrapper 更新不触发 MetaObjectHandler 自动填充，须显式写审计时间
                .set(Volunteer::getUpdateTime, LocalDateTime.now());
        volunteerMapper.update(null, uw);
    }

    /** 导出（与列表同筛选，不分页）。 */
    public List<VolunteerExportRow> exportRows(VolunteerQueryDTO dto) {
        List<Volunteer> all = volunteerMapper.selectList(buildWrapper(dto));
        Enrichment enrich = enrichmentFor(all);
        return all.stream().map(v -> {
            VolunteerExportRow row = new VolunteerExportRow();
            row.setName(v.getRealName());
            row.setGender(v.getGender() == null ? "" : v.getGender().getLabel());
            row.setPhone(decrypt(v.getPhone()));
            row.setSchool(v.getSchool());
            row.setGrade(v.getGrade() == null ? null : v.getGrade().getLabel());
            row.setPolitical(v.getPoliticalStatus() == null ? null : v.getPoliticalStatus().getLabel());
            row.setSquad(v.getSquadId() == null ? null : enrich.squadNames.get(v.getSquadId()));
            row.setGroup(enrich.groupNames.get(v.getId()));
            row.setManagerFlag(Integer.valueOf(1).equals(v.getManagerFlag()) ? "是" : "否");
            VolunteerServiceStatsView s = enrich.stats.get(v.getId());
            row.setHours(minutesToHours(s == null ? 0 : s.confirmedMinutes()));
            row.setPoints(s == null ? 0 : s.grantedPoints());
            row.setActivities(s == null ? 0 : s.activityCount());
            row.setStatus(statusLabel(v.getStatus()));
            return row;
        }).toList();
    }

    // ---------- 内部 ----------

    /** 按 id 取<b>已实名</b>志愿者；不存在或为游客（register_time 为空）一律按「不存在」拒绝。 */
    private Volunteer requireRegisteredVolunteer(Long id) {
        Volunteer v = volunteerMapper.selectById(id);
        if (v == null || v.getRegisterTime() == null) {
            throw new BusinessException("志愿者不存在");
        }
        return v;
    }

    /** user:edit 写死仅超管：必须是「启用中的超管」，否则拒绝（与 organization 同口径）。 */
    private void requireSuperAdmin(Long operatorAdminId) {
        AdminUser me = operatorAdminId == null ? null : adminUserMapper.selectById(operatorAdminId);
        if (me == null || !UserStatus.NORMAL.equals(me.getStatus())
                || !Integer.valueOf(1).equals(me.getIsSuperAdmin())) {
            throw new BusinessException("修改志愿者实名资料仅超级管理员可操作");
        }
    }

    private LambdaQueryWrapper<Volunteer> buildWrapper(VolunteerQueryDTO dto) {
        LambdaQueryWrapper<Volunteer> wrapper = Wrappers.<Volunteer>lambdaQuery()
                // 仅已实名志愿者（游客无姓名/学校等，不属「志愿者管理」范畴）
                .isNotNull(Volunteer::getRegisterTime);
        if (dto.getGender() != null) {
            wrapper.eq(Volunteer::getGender, parseGender(dto.getGender()));
        }
        if (dto.getPolitical() != null) {
            wrapper.eq(Volunteer::getPoliticalStatus, parsePolitical(dto.getPolitical()));
        }
        if (dto.getGrade() != null) {
            wrapper.eq(Volunteer::getGrade, parseGrade(dto.getGrade()));
        }
        if (dto.getSquad() != null) {
            wrapper.eq(Volunteer::getSquadId, dto.getSquad());
        }
        if (StringUtils.hasText(dto.getSchool())) {
            wrapper.like(Volunteer::getSchool, dto.getSchool().trim());
        }
        if (StringUtils.hasText(dto.getKeyword())) {
            String kw = dto.getKeyword().trim();
            boolean phoneLike = kw.matches("\\d{6,}");
            wrapper.and(w -> {
                w.like(Volunteer::getRealName, kw).or().like(Volunteer::getSchool, kw);
                if (phoneLike) {
                    w.or().eq(Volunteer::getPhoneHash, cryptoUtil.hashPhone(kw));
                }
            });
        }
        wrapper.orderByDesc(Volunteer::getId);
        return wrapper;
    }

    /** 主手机号变更落库：null=清空密文+hash；同号不重写；新号查重后重算。 */
    private void applyPhone(LambdaUpdateWrapper<Volunteer> uw, Volunteer current, String newPhone, Long id) {
        if (newPhone == null) {
            uw.set(Volunteer::getPhone, null).set(Volunteer::getPhoneHash, null);
            return;
        }
        String newHash = cryptoUtil.hashPhone(newPhone);
        if (newHash.equals(current.getPhoneHash())) {
            return; // 同号未变，不重写密文（避免无谓 churn）
        }
        Long clash = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, newHash)
                .ne(Volunteer::getId, id));
        if (clash != null && clash > 0) {
            throw new BusinessException("该手机号已被其他志愿者使用");
        }
        uw.set(Volunteer::getPhone, cryptoUtil.encrypt(newPhone)).set(Volunteer::getPhoneHash, newHash);
    }

    private Gender parseGender(Integer code) {
        if (code == null) {
            return null;
        }
        Gender g = Gender.fromCode(code);
        // Gender.fromCode 对未知码返回 UNKNOWN(0)，故须比对 code 反向确认是合法取值
        if (g == null || !g.getCode().equals(code)) {
            throw new BusinessException("性别取值非法");
        }
        return g;
    }

    private PoliticalStatus parsePolitical(Integer code) {
        if (code == null) {
            return null;
        }
        PoliticalStatus p = PoliticalStatus.fromCode(code);
        if (p == null) {
            throw new BusinessException("政治面貌取值非法");
        }
        return p;
    }

    private Grade parseGrade(Integer code) {
        if (code == null) {
            return null;
        }
        Grade g = Grade.fromCode(code);
        if (g == null) {
            throw new BusinessException("年级取值非法");
        }
        return g;
    }

    /** 一次性批量取列表行所需的跨域展示数据，避免逐行查询。 */
    private Enrichment enrichmentFor(List<Volunteer> records) {
        if (records.isEmpty()) {
            return new Enrichment(Map.of(), Map.of(), Map.of());
        }
        List<Long> ids = records.stream().map(Volunteer::getId).toList();
        List<Long> squadIds = records.stream().map(Volunteer::getSquadId).filter(Objects::nonNull).distinct().toList();
        return new Enrichment(
                serviceRecordService.batchStatsByVolunteerIds(ids),
                groupQueryService.listActiveGroupNamesByVolunteerIds(ids),
                squadQueryService.listNamesByIds(squadIds));
    }

    private void fillCommon(AdminVolunteerListVO vo, Volunteer v, Enrichment enrich) {
        vo.setId(v.getId());
        vo.setName(v.getRealName());
        Gender g = v.getGender();
        vo.setGender(g == null ? null : g.getLabel());
        vo.setGenderCode(g == null ? null : g.getCode());
        vo.setPhone(decrypt(v.getPhone()));
        vo.setManagerFlag(v.getManagerFlag() == null ? 0 : v.getManagerFlag());
        vo.setSchool(v.getSchool());
        Grade gr = v.getGrade();
        vo.setGrade(gr == null ? null : gr.getLabel());
        vo.setGradeCode(gr == null ? null : gr.getCode());
        PoliticalStatus p = v.getPoliticalStatus();
        vo.setPolitical(p == null ? null : p.getLabel());
        vo.setPoliticalCode(p == null ? null : p.getCode());
        vo.setSquadId(v.getSquadId());
        vo.setSquad(v.getSquadId() == null ? null : enrich.squadNames.get(v.getSquadId()));
        vo.setGroup(enrich.groupNames.get(v.getId()));
        VolunteerServiceStatsView s = enrich.stats.get(v.getId());
        vo.setHours(minutesToHours(s == null ? 0 : s.confirmedMinutes()));
        vo.setPoints(s == null ? 0 : s.grantedPoints());
        vo.setActivities(s == null ? 0 : s.activityCount());
        vo.setStatus(v.getStatus());
        vo.setRegisterTime(v.getRegisterTime());
    }

    private String decrypt(String stored) {
        return StringUtils.hasText(stored) ? cryptoUtil.decrypt(stored) : null;
    }

    private String joinEmergency(String name, String phone) {
        String n = name == null ? "" : name.trim();
        String p = phone == null ? "" : phone.trim();
        String combined = (n + " " + p).trim();
        return combined.isEmpty() ? null : combined;
    }

    /** 分钟换算小时，保留 1 位小数。 */
    private Double minutesToHours(int minutes) {
        return Math.round(minutes * 10.0 / 60.0) / 10.0;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String statusLabel(Integer status) {
        if (UserStatus.BANNED.equals(status)) {
            return "禁用";
        }
        if (UserStatus.DELETED.equals(status)) {
            return "已注销";
        }
        return "正常";
    }

    /** 一页内的跨域批量聚合结果。 */
    private record Enrichment(Map<Long, VolunteerServiceStatsView> stats,
                              Map<Long, String> groupNames,
                              Map<Long, String> squadNames) {
    }
}
