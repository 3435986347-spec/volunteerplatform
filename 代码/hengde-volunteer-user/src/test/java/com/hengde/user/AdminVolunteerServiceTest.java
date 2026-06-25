package com.hengde.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.user.dto.VolunteerQueryDTO;
import com.hengde.user.dto.VolunteerUpdateDTO;
import com.hengde.user.service.AdminVolunteerService;
import com.hengde.user.vo.AdminVolunteerDetailVO;
import com.hengde.user.vo.AdminVolunteerListVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者管理服务集成测试。<b>需本机 Docker</b>（MySQL + Redis——经活动域传递引入 redisson starter）。
 *
 * <p>领域模块测试上下文无分页拦截器（仅 api 装配），故 {@code selectPage} 不加 LIMIT、records 返回全部匹配行，
 * 断言以 records 内容为准（用唯一学校名收敛，避免跨用例数据干扰）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AdminVolunteerServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private AdminVolunteerService service;
    private VolunteerMapper volunteerMapper;
    private AdminUserMapper adminUserMapper;
    private CryptoUtil cryptoUtil;
    private VolunteerSquadMapper squadMapper;
    private VolunteerGroupMapper groupMapper;
    private VolunteerGroupMemberMapper memberMapper;
    private ActivityAttendanceMapper attendanceMapper;
    private JdbcTemplate jdbcTemplate;

    @Autowired
    public void setService(AdminVolunteerService service) {
        this.service = service;
    }

    @Autowired
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    @Autowired
    public void setGroupMapper(VolunteerGroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    @Autowired
    public void setMemberMapper(VolunteerGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Test
    void listExcludesGuestsAndMatchesKeyword() {
        String school = "测试学校_" + SEQ.incrementAndGet() + "_" + System.nanoTime();
        Long registered = insertRegistered("张三", "13800000000", null, school, null);
        Long guest = insertGuest(school); // 未实名（registerTime=null），同校名

        VolunteerQueryDTO dto = new VolunteerQueryDTO();
        dto.setKeyword(school);
        PageResult<AdminVolunteerListVO> page = service.list(dto);

        assertTrue(page.getRecords().stream().anyMatch(r -> r.getId().equals(registered)),
                "已实名志愿者应在列表中");
        assertFalse(page.getRecords().stream().anyMatch(r -> r.getId().equals(guest)),
                "游客（未实名）不应出现在志愿者管理列表");
    }

    @Test
    void keywordMatchesPhoneByHash() {
        String phone = "139" + String.format("%08d", SEQ.incrementAndGet());
        String school = "手机搜校_" + System.nanoTime();
        Long id = insertRegistered("李四", phone, null, school, null);

        VolunteerQueryDTO dto = new VolunteerQueryDTO();
        dto.setKeyword(phone);
        PageResult<AdminVolunteerListVO> page = service.list(dto);

        assertTrue(page.getRecords().stream().anyMatch(r -> r.getId().equals(id)),
                "按手机号（纯数字）应经 phoneHash 精确命中");
    }

    @Test
    void detailDecryptsPiiAndAggregatesAcrossDomains() {
        // 分队
        VolunteerSquad squad = new VolunteerSquad();
        squad.setName("测试分队_" + System.nanoTime());
        squad.setType("学校");
        squad.setStatus(1);
        squadMapper.insert(squad);

        String idCard = "440000200001011234"; // 末 4 位 1234
        Long id = insertRegistered("王五", "13700000000", idCard, "聚合校", squad.getId());

        // 小组（ACTIVE 成员）
        VolunteerGroup group = new VolunteerGroup();
        group.setGroupNo("G_" + System.nanoTime());
        group.setName("测试小组_" + System.nanoTime());
        group.setLeaderId(id);
        group.setStatus(1);
        groupMapper.insert(group);
        VolunteerGroupMember m = new VolunteerGroupMember();
        m.setGroupId(group.getId());
        m.setVolunteerId(id);
        m.setRole(1);
        m.setStatus(1); // ACTIVE
        m.setApplyTime(LocalDateTime.now());
        memberMapper.insert(m);

        // 两条考勤：A 已确认+已发放(120min/50分)，B 仅参与未确认
        insertAttendance(id, 9001L, 120, 1, 50, 1);
        insertAttendance(id, 9002L, 60, 0, 0, 0);

        AdminVolunteerDetailVO vo = service.detail(id);

        assertEquals("13700000000", vo.getPhone(), "手机号应解密回显");
        assertEquals("1234", vo.getIdTail(), "身份证应只回尾 4 位");
        assertEquals(squad.getName(), vo.getSquad(), "应解析归属分队名");
        assertEquals(group.getName(), vo.getGroup(), "应解析所在小组名");
        assertEquals(2, vo.getActivities(), "参与活动按 activity_id 去重计 2");
        assertEquals(2.0, vo.getHours(), 0.001, "已确认 120min = 2.0h（未确认的 B 不计）");
        assertEquals(50, vo.getPoints(), "仅已发放积分计入");
    }

    @Test
    void updateChangesPhoneRecomputesHashAndRejectsClash() {
        Long su = insertSuperAdmin();
        String p1 = "135" + String.format("%08d", SEQ.incrementAndGet());
        String p2 = "136" + String.format("%08d", SEQ.incrementAndGet());
        String p3 = "137" + String.format("%08d", SEQ.incrementAndGet());
        Long a = insertRegistered("甲", p1, null, "改号校", null);
        insertRegistered("乙", p2, null, "改号校", null);

        // 改成全新号 p3：phoneHash 应重算
        VolunteerUpdateDTO ok = baseUpdate("甲改名");
        ok.setPhone(p3);
        service.updateBy(a, ok, su);
        Long hit = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getPhoneHash, cryptoUtil.hashPhone(p3)).eq(Volunteer::getId, a));
        assertEquals(1L, hit, "改号后应能按新 phoneHash 命中");
        assertEquals("甲改名", volunteerMapper.selectById(a).getRealName());

        // 改成乙的号 p2：查重应拒绝
        VolunteerUpdateDTO clash = baseUpdate("甲");
        clash.setPhone(p2);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateBy(a, clash, su));
        assertTrue(ex.getMessage().contains("已被其他志愿者使用"));
    }

    @Test
    void updateRejectedForNonSuperAdmin() {
        Long sub = insertSubAdmin(); // 非超管
        Long id = insertRegistered("敏感", "13511112222", null, "权限校", null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateBy(id, baseUpdate("改名"), sub));
        assertTrue(ex.getMessage().contains("超级管理员"), "user:edit 写死仅超管");
    }

    @Test
    void updateClearsClearableFields() {
        Long su = insertSuperAdmin();
        // 起始：有分队、有学校、有紧急联系人电话
        VolunteerSquad squad = new VolunteerSquad();
        squad.setName("清空分队_" + System.nanoTime());
        squad.setType("学校");
        squad.setStatus(1);
        squadMapper.insert(squad);
        Long id = insertRegistered("清空", "13522223333", null, "清空校", squad.getId());
        Volunteer seed = new Volunteer();
        seed.setId(id);
        seed.setEmergencyContactPhone(cryptoUtil.encrypt("13800000000"));
        volunteerMapper.updateById(seed);

        // 全量 PUT：squadId/school/紧急联系电话 留空 → 清空
        VolunteerUpdateDTO dto = baseUpdate("清空");
        service.updateBy(id, dto, su);

        Volunteer after = volunteerMapper.selectById(id);
        assertNull(after.getSquadId(), "传 null 应清空 squadId（全量 PUT）");
        assertNull(after.getSchool(), "传 null 应清空 school");
        assertNull(after.getEmergencyContactPhone(), "留空应清空紧急联系电话密文");
    }

    @Test
    void updateRejectsIllegalEnumCode() {
        Long su = insertSuperAdmin();
        Long id = insertRegistered("枚举", "13533334444", null, "枚举校", null);
        VolunteerUpdateDTO dto = baseUpdate("枚举");
        dto.setGrade(999); // 非法年级 code
        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateBy(id, dto, su));
        assertTrue(ex.getMessage().contains("年级"), "非法枚举 code 应抛业务异常而非静默忽略");
    }

    @Test
    void byIdOperationsRejectGuestRows() {
        Long su = insertSuperAdmin();
        Long guest = insertGuest("游客校_" + System.nanoTime());
        // 知道游客 id 也不能读取/改/停/删/重置
        assertThrows(BusinessException.class, () -> service.detail(guest));
        assertThrows(BusinessException.class, () -> service.updateBy(guest, baseUpdate("x"), su));
        assertThrows(BusinessException.class, () -> service.setStatus(guest, 1));
        assertThrows(BusinessException.class, () -> service.delete(guest));
        assertThrows(BusinessException.class, () -> service.resetPassword(guest));
    }

    @Test
    void setStatusRejectsLogoutCodeAndAcceptsBan() {
        Long id = insertRegistered("丙", "13400000000", null, "状态校", null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.setStatus(id, 2));
        assertTrue(ex.getMessage().contains("0") && ex.getMessage().contains("1"));

        service.setStatus(id, 1);
        assertEquals(1, volunteerMapper.selectById(id).getStatus());
    }

    @Test
    void deleteReleasesUniqueFieldsAndScrubsCredentials() {
        String phone = "133" + String.format("%08d", SEQ.incrementAndGet());
        String idCard = "440000200002022345";
        Long id = insertRegistered("丁", phone, idCard, "删校", null);
        // 补 password + 紧急联系电话密文，验证删除时一并清空
        Volunteer seed = new Volunteer();
        seed.setId(id);
        seed.setPassword("bcrypt-hash-x");
        seed.setEmergencyContactPhone(cryptoUtil.encrypt("13800000000"));
        volunteerMapper.updateById(seed);

        service.delete(id);

        // active 查询隐藏（@TableLogic）
        assertNull(volunteerMapper.selectById(id), "逻辑删除后按 id 查应被隐藏");

        // 含逻辑删除的原始行（绕过 @TableLogic 直查）：唯一字段释放 + 凭据/敏感密文清空
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT openid, phone, phone_hash, id_card_no, id_card_hash, password, "
                        + "emergency_contact_phone, is_deleted FROM volunteer WHERE id = ?", id);
        assertEquals(1, ((Number) row.get("is_deleted")).intValue(), "应已逻辑删除");
        assertEquals("deleted:" + id, row.get("openid"), "openid 应改写为占位、释放唯一键");
        assertNull(row.get("phone_hash"), "phone_hash 应释放 → 同号可重新注册");
        assertNull(row.get("id_card_hash"), "id_card_hash 应释放");
        assertNull(row.get("password"), "password 应清空");
        assertNull(row.get("phone"), "手机号密文应清空");
        assertNull(row.get("id_card_no"), "身份证密文应清空");
        assertNull(row.get("emergency_contact_phone"), "紧急联系电话密文应清空");

        // 唯一键确实释放：用同一手机号重新注册新志愿者应成功（不撞 uk_phone_hash）
        Long reId = insertRegistered("丁二次", phone, idCard, "删校", null);
        assertNotNull(reId);
        assertNotEquals(id, reId);
    }

    @Test
    void resetPasswordIsNoOpForExistingVolunteer() {
        Long id = insertRegistered("戊", "13200000000", null, "重置校", null);
        service.resetPassword(id); // 不抛异常即视为契约兼容 no-op
        assertNotNull(volunteerMapper.selectById(id));
    }

    // ---------- helpers ----------

    private VolunteerUpdateDTO baseUpdate(String name) {
        VolunteerUpdateDTO dto = new VolunteerUpdateDTO();
        dto.setRealName(name);
        return dto;
    }

    private Long insertRegistered(String name, String phonePlain, String idCardPlain, String school, Long squadId) {
        Volunteer v = new Volunteer();
        v.setOpenid("u_test_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName(name);
        v.setGender(Gender.MALE);
        v.setSchool(school);
        v.setSquadId(squadId);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        if (phonePlain != null) {
            v.setPhone(cryptoUtil.encrypt(phonePlain));
            v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        }
        if (idCardPlain != null) {
            v.setIdCardNo(cryptoUtil.encrypt(idCardPlain));
            v.setIdCardHash(cryptoUtil.hashIdCard(idCardPlain));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertSuperAdmin() {
        return insertAdmin(1);
    }

    private Long insertSubAdmin() {
        return insertAdmin(0);
    }

    private Long insertAdmin(int isSuper) {
        AdminUser a = new AdminUser();
        a.setUsername("uv_admin_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        a.setPassword("x");
        a.setIsSuperAdmin(isSuper);
        a.setStatus(0);
        adminUserMapper.insert(a);
        return a.getId();
    }

    private Long insertGuest(String school) {
        Volunteer v = new Volunteer();
        v.setOpenid("u_guest_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setSchool(school);
        v.setStatus(0);
        // registerTime 为空 = 游客
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void insertAttendance(Long volunteerId, Long activityId, int minutes,
                                  int secretaryStatus, int pointsAward, int pointsStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setVolunteerId(volunteerId);
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(secretaryStatus);
        att.setPointsAward(pointsAward);
        att.setPointsStatus(pointsStatus);
        attendanceMapper.insert(att);
    }
}
