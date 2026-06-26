package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.PoliticalStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import com.hengde.user.dto.MyProfileUpdateDTO;
import com.hengde.user.service.MyProfileService;
import com.hengde.user.vo.MyProfileVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者端「我的资料」服务集成测试。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p>service 直接收 volunteerId（不碰 Sa-Token），故读/改/校验路径均可在非 web 上下文直接测。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MyProfileServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private MyProfileService service;
    private VolunteerMapper volunteerMapper;
    private CryptoUtil cryptoUtil;
    private VerifyCodeService verifyCodeService;
    private RedisUtil redisUtil;

    @Autowired
    public void setService(MyProfileService service) {
        this.service = service;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setVerifyCodeService(VerifyCodeService verifyCodeService) {
        this.verifyCodeService = verifyCodeService;
    }

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    private String storedCode(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    private static String phone(String prefix) {
        return prefix + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void getMyProfile_registered_decryptsAndMapsLabels() {
        String phone = "138" + String.format("%08d", SEQ.incrementAndGet());
        // 身份证号须全局唯一（volunteer.uk_id_card_hash 唯一约束；与其它测试类共享容器合跑时会撞），末 4 位固定 1234 供断言
        String idCard = "4401" + String.format("%010d", System.nanoTime() % 10_000_000_000L) + "1234";
        Volunteer v = new Volunteer();
        v.setOpenid("p_test_" + System.nanoTime());
        v.setRealName("赵六");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setIdCardNo(cryptoUtil.encrypt(idCard));
        v.setIdCardHash(cryptoUtil.hashIdCard(idCard));
        v.setGender(Gender.MALE);
        v.setPoliticalStatus(PoliticalStatus.LEAGUE_MEMBER); // 共青团员 / 2
        v.setGrade(Grade.SENIOR_1);                          // 高一 / 10
        v.setSchool("我的资料校_" + System.nanoTime());
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);

        MyProfileVO vo = service.getMyProfile(v.getId());
        assertTrue(vo.isRegistered(), "已实名");
        assertEquals("赵六", vo.getRealName());
        assertEquals(phone, vo.getPhone(), "手机号应解密回显");
        assertEquals("1234", vo.getIdTail(), "身份证保留脱敏尾 4 位");
        assertEquals(idCard, vo.getIdCardNo(), "本人查看自己应回完整身份证号");
        assertEquals(Integer.valueOf(2), vo.getPoliticalStatus());
        assertEquals("共青团员", vo.getPoliticalStatusName());
        assertEquals(Integer.valueOf(10), vo.getGrade());
        assertEquals("高一", vo.getGradeName());
        assertEquals(v.getSchool(), vo.getSchool());
    }

    @Test
    void getMyProfile_guest_noCrashAndNotRegistered() {
        Volunteer v = new Volunteer();
        v.setOpenid("p_guest_" + System.nanoTime());
        v.setStatus(0); // registerTime 为空 = 游客
        volunteerMapper.insert(v);

        MyProfileVO vo = service.getMyProfile(v.getId());
        assertFalse(vo.isRegistered(), "游客未实名");
        assertNull(vo.getRealName());
        assertEquals(0, vo.getServiceMinutes(), "无考勤 → 0");
        assertEquals(0, vo.getActivityCount());
    }

    @Test
    void getMyProfile_missing_rejected() {
        assertThrows(BusinessException.class, () -> service.getMyProfile(-9999L));
    }

    @Test
    void updateMyProfile_updatesEditableFields() {
        Long id = insertGuestWithPhone("139" + String.format("%08d", SEQ.incrementAndGet()));
        MyProfileUpdateDTO dto = new MyProfileUpdateDTO();
        String school = "改后校_" + System.nanoTime();
        dto.setSchool(school);
        dto.setGrade(10);                 // 高一
        dto.setPoliticalStatus(4);        // 中共党员
        dto.setAddress("广东省湛江市雷州市");
        dto.setAvatarUrl("https://oss/avatar.png");
        dto.setIVolunteerCodeUrl("https://oss/ivcode.png");
        String nick = "昵称_" + System.nanoTime();
        dto.setNickName(nick);
        String emergency = "137" + String.format("%08d", SEQ.incrementAndGet());
        dto.setEmergencyContactPhone(emergency);

        service.updateMyProfile(id, dto);

        Volunteer r = volunteerMapper.selectById(id);
        assertEquals(school, r.getSchool());
        assertEquals(Grade.SENIOR_1, r.getGrade());
        assertEquals(PoliticalStatus.CPC_MEMBER, r.getPoliticalStatus());
        assertEquals("广东省湛江市雷州市", r.getAddress());
        assertEquals("https://oss/avatar.png", r.getAvatarUrl());
        assertEquals("https://oss/ivcode.png", r.getIVolunteerCodeUrl(), "i志愿者码应可上传修改");
        assertEquals(nick, r.getNickName(), "昵称应可修改");
        assertEquals(emergency, cryptoUtil.decrypt(r.getEmergencyContactPhone()), "紧急联系电话应加密落库");
    }

    @Test
    void updateMyProfile_duplicateNickName_rejected() {
        String nick = "占用昵称_" + System.nanoTime();
        Volunteer taken = new Volunteer();
        taken.setOpenid("p_nick_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        taken.setNickName(nick);
        taken.setStatus(0);
        volunteerMapper.insert(taken);

        Long id = insertGuestWithPhone(phone("133"));
        MyProfileUpdateDTO dto = new MyProfileUpdateDTO();
        dto.setNickName(nick); // 撞别人的昵称
        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateMyProfile(id, dto));
        assertTrue(ex.getMessage().contains("昵称"), "应提示昵称已被使用");
    }

    @Test
    void updateMyProfile_invalidGrade_rejected() {
        Long id = insertGuestWithPhone("136" + String.format("%08d", SEQ.incrementAndGet()));
        MyProfileUpdateDTO dto = new MyProfileUpdateDTO();
        dto.setGrade(99); // 非法 code（@Min/@Max 在控制器，service 侧 parseGrade 兜底）
        assertThrows(BusinessException.class, () -> service.updateMyProfile(id, dto));
    }

    @Test
    void updateMyProfile_emergencySameAsOwnPhone_rejected() {
        String phone = "135" + String.format("%08d", SEQ.incrementAndGet());
        Long id = insertGuestWithPhone(phone);
        MyProfileUpdateDTO dto = new MyProfileUpdateDTO();
        dto.setEmergencyContactPhone(phone); // 与本人手机号相同
        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateMyProfile(id, dto));
        assertTrue(ex.getMessage().contains("本人手机号"));
    }

    @Test
    void changePhone_success_updatesPhoneAndHash() {
        Long id = insertGuestWithPhone(phone("138"));
        String newPhone = phone("139");
        verifyCodeService.sendCode(newPhone, SmsScene.CHANGE_PHONE, null);
        service.changePhone(id, newPhone, storedCode(SmsScene.CHANGE_PHONE, newPhone));
        Volunteer r = volunteerMapper.selectById(id);
        assertEquals(cryptoUtil.hashPhone(newPhone), r.getPhoneHash(), "phoneHash 应更新为新号");
        assertEquals(newPhone, cryptoUtil.decrypt(r.getPhone()), "phone 密文应为新号");
    }

    @Test
    void changePhone_wrongCode_rejected() {
        Long id = insertGuestWithPhone(phone("136"));
        String newPhone = phone("137");
        verifyCodeService.sendCode(newPhone, SmsScene.CHANGE_PHONE, null);
        assertThrows(BusinessException.class, () -> service.changePhone(id, newPhone, "000000"));
    }

    @Test
    void changePhone_clashWithOtherAccount_rejected() {
        String taken = phone("135");
        insertGuestWithPhone(taken); // 另一账号已占用该号
        Long id = insertGuestWithPhone(phone("134"));
        verifyCodeService.sendCode(taken, SmsScene.CHANGE_PHONE, null);
        String code = storedCode(SmsScene.CHANGE_PHONE, taken);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.changePhone(id, taken, code));
        assertTrue(ex.getMessage().contains("已被"), "应提示手机号已被占用");
    }

    private Long insertGuestWithPhone(String phonePlain) {
        Volunteer v = new Volunteer();
        v.setOpenid("p_upd_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setPhone(cryptoUtil.encrypt(phonePlain));
        v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        v.setStatus(0);
        volunteerMapper.insert(v);
        return v.getId();
    }
}
