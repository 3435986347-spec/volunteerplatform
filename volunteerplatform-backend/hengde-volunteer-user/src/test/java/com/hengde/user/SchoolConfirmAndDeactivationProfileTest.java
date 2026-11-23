package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.UserStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的资料」里的两件新事：每年确认一次学校年级、注销冷静期的展示与手机号释放。
 *
 * <p>协会 2026-09-16：学校 / 年级「每年由志愿者确认即可，审核自动通过，原则上不影响报名」；
 * 注销后手机号释放（别人换绑到这个号不该被一个已过冷静期的账号挡住）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SchoolConfirmAndDeactivationProfileTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    private MyProfileService profileService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private VerifyCodeService verifyCodeService;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schoolConfirm_isAskedOncePerSchoolYear_andClearsTheGradePrompt() {
        int thisYear = Grade.schoolYearOf(LocalDate.now());
        Long id = volunteer(phone(), thisYear - 1);
        jdbc.update("UPDATE volunteer SET grade_prompt_pending = 1 WHERE id = ?", id);

        MyProfileVO before = profileService.getMyProfile(id);
        assertTrue(before.getSchoolConfirmPending(), "上一学年确认的，这个学年要再确认一次");
        assertEquals(thisYear - 1, before.getSchoolConfirmYear());

        assertThrows(BusinessException.class, () -> profileService.confirmSchoolAndGrade(id, " ", Grade.SENIOR_2.getCode()));
        assertThrows(BusinessException.class, () -> profileService.confirmSchoolAndGrade(id, "某中学", 99), "年级取值非法");

        profileService.confirmSchoolAndGrade(id, "  雷州一中  ", Grade.SENIOR_2.getCode());
        Volunteer v = volunteerMapper.selectById(id);
        assertEquals("雷州一中", v.getSchool());
        assertEquals(Grade.SENIOR_2, v.getGrade());
        assertEquals(thisYear, v.getSchoolConfirmYear());
        assertEquals(thisYear, v.getGradeUpgradeYear(), "确认的年级就是本学年的年级，升级任务下个 9 月才动它");
        assertEquals(0, v.getGradePromptPending(), "挂着的「请改学校和年级」提示一并清除");

        MyProfileVO after = profileService.getMyProfile(id);
        assertFalse(after.getSchoolConfirmPending(), "本学年确认过就不再提示");

        // 从没记过的（存量回填不到的）也提示；游客不提示
        Long never = volunteer(phone(), null);
        assertTrue(profileService.getMyProfile(never).getSchoolConfirmPending());
        Long guest = guest(phone());
        assertFalse(profileService.getMyProfile(guest).getSchoolConfirmPending(), "游客没有学校年级可确认");
        assertThrows(BusinessException.class, () -> profileService.confirmSchoolAndGrade(guest, "某中学", Grade.SENIOR_1.getCode()));
    }

    /** 在「基本信息」里改了学校或年级，就算这个学年确认过了——不然弹窗会在他刚改完之后又弹出来。 */
    @Test
    void editingSchoolOrGradeInTheProfile_countsAsThisYearsConfirmation() {
        int thisYear = Grade.schoolYearOf(LocalDate.now());
        Long bySchool = volunteer(phone(), thisYear - 1);
        MyProfileUpdateDTO school = new MyProfileUpdateDTO();
        school.setSchool("新学校");
        profileService.updateMyProfile(bySchool, school);
        assertFalse(profileService.getMyProfile(bySchool).getSchoolConfirmPending());
        assertEquals(thisYear, volunteerMapper.selectById(bySchool).getSchoolConfirmYear());

        Long byGrade = volunteer(phone(), thisYear - 1);
        MyProfileUpdateDTO grade = new MyProfileUpdateDTO();
        grade.setGrade(Grade.SENIOR_2.getCode());
        profileService.updateMyProfile(byGrade, grade);
        assertFalse(profileService.getMyProfile(byGrade).getSchoolConfirmPending());

        // 只改了别的（地址）不算确认
        Long byAddress = volunteer(phone(), thisYear - 1);
        MyProfileUpdateDTO address = new MyProfileUpdateDTO();
        address.setAddress("雷州市某某路");
        profileService.updateMyProfile(byAddress, address);
        assertTrue(profileService.getMyProfile(byAddress).getSchoolConfirmPending());
    }

    @Test
    void profile_showsTheCoolingOffWindow() {
        Long id = volunteer(phone(), Grade.schoolYearOf(LocalDate.now()));
        assertNull(profileService.getMyProfile(id).getDeactivateEffectiveTime());
        jdbc.update("UPDATE volunteer SET deactivate_request_time = NOW(), "
                + "deactivate_effective_time = DATE_ADD(NOW(), INTERVAL 7 DAY) WHERE id = ?", id);
        MyProfileVO vo = profileService.getMyProfile(id);
        assertNotNull(vo.getDeactivateRequestTime());
        assertTrue(vo.getDeactivateEffectiveTime().isAfter(LocalDateTime.now().plusDays(6)));
    }

    /** 换绑到一个号，而它的持有者已过注销冷静期：先释放再换绑，不该报「已被其他账号使用」。 */
    @Test
    void changePhone_toANumberHeldByAnExpiredCoolingOffAccount_succeeds() {
        String taken = phone();
        Long leaving = volunteer(taken, Grade.schoolYearOf(LocalDate.now()));
        String mine = phone();
        Long me = volunteer(mine, Grade.schoolYearOf(LocalDate.now()));

        verifyCodeService.sendCode(taken, SmsScene.CHANGE_PHONE, null);
        String code = code(SmsScene.CHANGE_PHONE, taken);
        verifyCodeService.sendCode(mine, SmsScene.CHANGE_PHONE, null);
        String oldCode = code(SmsScene.CHANGE_PHONE, mine);
        assertEquals("该手机号已被其他账号使用",
                assertThrows(BusinessException.class, () -> profileService.changePhone(me, taken, code, oldCode))
                        .getMessage(),
                "对方没注销时照旧挡住");

        jdbc.update("UPDATE volunteer SET deactivate_request_time = DATE_SUB(NOW(), INTERVAL 8 DAY), "
                + "deactivate_effective_time = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = ?", leaving);
        verifyCodeService.sendCode(taken, SmsScene.CHANGE_PHONE, null);
        verifyCodeService.sendCode(mine, SmsScene.CHANGE_PHONE, null);
        profileService.changePhone(me, taken, code(SmsScene.CHANGE_PHONE, taken), code(SmsScene.CHANGE_PHONE, mine));

        assertEquals(cryptoUtil.hashPhone(taken), volunteerMapper.selectById(me).getPhoneHash());
        Volunteer gone = volunteerMapper.selectById(leaving);
        assertEquals(UserStatus.DELETED, gone.getStatus(), "对方到期即注销");
        assertNull(gone.getPhoneHash());
    }

    // ---------------- helpers ----------------

    private Long volunteer(String phone, Integer confirmYear) {
        long n = SEQ.incrementAndGet();
        String idCard = "4402" + String.format("%010d", n) + "567X";
        Volunteer v = new Volunteer();
        v.setOpenid("p:" + cryptoUtil.hashPhone(phone).substring(0, 62));
        v.setRealName("确认测试" + n);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setIdCardNo(cryptoUtil.encrypt(idCard));
        v.setIdCardHash(cryptoUtil.hashIdCard(idCard));
        v.setSchool("原学校");
        v.setGrade(Grade.SENIOR_1);
        v.setSchoolConfirmYear(confirmYear);
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long guest(String phone) {
        Volunteer v = new Volunteer();
        v.setOpenid("p:" + cryptoUtil.hashPhone(phone).substring(0, 62));
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(UserStatus.NORMAL);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private String code(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    private static String phone() {
        return "135" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000L);
    }
}
