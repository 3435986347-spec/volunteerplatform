package com.hengde.enterprise;

import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.sms.SmsScene;
import com.hengde.common.sms.VerifyCodeService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.service.EnterpriseAccountService;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterpriseAuthService;
import com.hengde.enterprise.service.EnterprisePublicService;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.hengde.enterprise.EnterpriseTestSupport.ADMIN;
import static com.hengde.enterprise.EnterpriseTestSupport.assertMessage;
import static com.hengde.enterprise.EnterpriseTestSupport.creditCode;
import static com.hengde.enterprise.EnterpriseTestSupport.inRequest;
import static com.hengde.enterprise.EnterpriseTestSupport.login;
import static com.hengde.enterprise.EnterpriseTestSupport.phone;
import static com.hengde.enterprise.EnterpriseTestSupport.register;
import static com.hengde.enterprise.EnterpriseTestSupport.username;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业账号（V4 爱心企业批·账号段，Row 15 / Row 49）：注册（先查格式与唯一、最后核验证码）/ 登录与状态 / 后台审核暂停删除 /
 * 资料修改的身份字段限制 / 公开展示不露私密字段 / 后台代建与导出 / 找回密码。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseAccountTest {

    @Autowired
    private EnterpriseAuthService authService;
    @Autowired
    private EnterpriseAccountService accountService;
    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private EnterprisePublicService publicService;
    @Autowired
    private EnterpriseAccountMapper accountMapper;
    @Autowired
    private VerifyCodeService verifyCodeService;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    private String code(String phone) {
        return EnterpriseTestSupport.sendAndReadCode(verifyCodeService, redisUtil, phone, SmsScene.ENTERPRISE_REGISTER);
    }

    private Long registered(String user) {
        String p = phone();
        return authService.register(register(user, creditCode(), p, code(p)));
    }

    @Test
    void register_formatAndUniquenessFirst_codeLast_pendingWithEncryptedPhone() {
        String p = phone();
        String code = code(p);
        String user = username("reg_");
        assertMessage("统一社会信用代码不正确", () -> authService.register(register(user, "91440882MA0000000X", p, code)));
        EnterpriseDTOs.Register external = register(user, creditCode(), p, code);
        external.setLogoUrl("https://evil.example.com/a.png");
        assertMessage("企业头像无效", () -> authService.register(external));
        assertMessage("验证码", () -> authService.register(register(user, creditCode(), p, "000000".equals(code) ? "111111" : "000000")));

        String cc = creditCode().toLowerCase(Locale.ROOT);
        // 前面几次失败都在核验证码之前（或只是输错一次），那条码仍然有效——填错一个信用代码不该让负责人重收短信
        Long id = authService.register(register(user, " " + cc + " ", p, code));
        EnterpriseAccount a = accountMapper.selectById(id);
        assertEquals(0, a.getStatus(), "注册落待审核");
        assertEquals(cc.toUpperCase(Locale.ROOT), a.getCreditCode(), "信用代码去空白转大写");
        assertNotEquals(p, a.getLeaderPhone(), "负责人手机号是密文");
        assertEquals(p, cryptoUtil.decrypt(a.getLeaderPhone()));
        assertNotEquals("pass1234", a.getPassword());

        String p2 = phone();
        assertMessage("登录账号已经被注册", () -> authService.register(register(user.toUpperCase(Locale.ROOT), creditCode(), p2, code(p2))));
        String p3 = phone();
        assertMessage("统一社会信用代码已经入驻过", () -> authService.register(register(username("reg_"), cc, p3, code(p3))));

        adminService.delete(id, ADMIN);
        String p4 = phone();
        assertTrue(authService.register(register(user, cc, p4, code(p4))) > 0, "删除即释放登录账号与信用代码");
    }

    @Test
    void login_pendingAllowed_pausedRejected_deletedAndLockout() throws Exception {
        String user = username("login_");
        Long id = registered(user);
        EnterpriseVOs.LoginResult r = inRequest(() -> authService.login(login(user, "pass1234"), null));
        assertEquals(0, r.getStatus(), "待审核能登录看状态");
        assertTrue(r.getToken() != null && !r.getToken().isEmpty());
        assertMessage("账号或密码错误", () -> authService.login(login(user, "wrong-pass"), null));

        adminService.approve(id, ADMIN);
        adminService.pause(id, "赞助商品长期缺货", ADMIN);
        assertMessage("企业账号已暂停：赞助商品长期缺货", () -> authService.login(login(user, "pass1234"), null));
        adminService.resume(id, ADMIN);
        assertEquals(1, inRequest(() -> authService.login(login(user.toUpperCase(Locale.ROOT), "pass1234"), null)).getStatus(),
                "登录账号不区分大小写");

        adminService.delete(id, ADMIN);
        assertMessage("账号或密码错误", () -> authService.login(login(user, "pass1234"), null));

        String locked = username("lock_");
        registered(locked);
        for (int i = 0; i < 5; i++) {
            assertMessage("账号或密码错误", () -> authService.login(login(locked, "bad-bad"), null));
        }
        assertMessage("锁定", () -> authService.login(login(locked, "pass1234"), null));
    }

    @Test
    void adminLifecycle_casTransitions_andLoserSeesCurrentState() {
        Long id = registered(username("life_"));
        assertMessage("请填写原因", () -> adminService.reject(id, " ", ADMIN));
        adminService.reject(id, "信用代码与营业执照不符", ADMIN);
        assertMessage("只有待审核的入驻申请可以通过（当前：已驳回）", () -> adminService.approve(id, ADMIN));
        assertEquals("信用代码与营业执照不符", accountService.mine(id).getRejectReason());

        accountService.resubmit(id);
        assertMessage("只有被驳回的入驻申请可以重新提交（当前：待审核）", () -> accountService.resubmit(id));
        adminService.approve(id, ADMIN);
        EnterpriseVOs.Account ok = adminService.detail(id);
        assertEquals(1, ok.getStatus());
        assertNull(ok.getRejectReason(), "通过即清掉上次的驳回原因");
        assertMessage("只有已暂停的企业可以恢复（当前：正常）", () -> adminService.resume(id, ADMIN));
        adminService.pause(id, "暂停", ADMIN);
        assertMessage("只有正常的企业可以暂停（当前：已暂停）", () -> adminService.pause(id, "再暂停", ADMIN));
        adminService.resume(id, ADMIN);
        adminService.delete(id, ADMIN);
        assertMessage("企业不存在或已删除", () -> adminService.delete(id, ADMIN));
        assertMessage("企业不存在或已删除", () -> adminService.approve(id, ADMIN));
    }

    @Test
    void profile_identityOnlyBeforeApproval_displayFieldsAlways() {
        Long id = registered(username("prof_"));
        String newCode = creditCode();
        EnterpriseDTOs.Profile rename = new EnterpriseDTOs.Profile();
        rename.setName("改过名的企业");
        rename.setCreditCode(newCode);
        rename.setIntro("新的介绍");
        EnterpriseVOs.Account pending = accountService.updateProfile(id, rename);
        assertEquals("改过名的企业", pending.getName(), "待审核时身份信息可以改");
        assertEquals(newCode, pending.getCreditCode());

        adminService.approve(id, ADMIN);
        EnterpriseDTOs.Profile again = new EnterpriseDTOs.Profile();
        again.setName("审核通过后偷偷改名");
        again.setIntro("介绍也一起改");
        assertMessage("审核通过之后企业名称", () -> accountService.updateProfile(id, again));
        assertEquals("新的介绍", accountService.mine(id).getIntro(), "整条更新被拒，介绍也没改");

        EnterpriseDTOs.Profile display = new EnterpriseDTOs.Profile();
        display.setName("改过名的企业");
        display.setIntro("通过后改介绍");
        display.setAddress("新地址");
        display.setContactPhone("0759-1234567");
        EnterpriseVOs.Account normal = accountService.updateProfile(id, display);
        assertEquals("通过后改介绍", normal.getIntro(), "名称没变就不算改身份，展示字段随时能改");
        assertEquals("新地址", normal.getAddress());

        EnterpriseDTOs.Profile logo = new EnterpriseDTOs.Profile();
        logo.setLogoUrl("https://evil.example.com/x.png");
        assertMessage("企业头像无效", () -> accountService.updateProfile(id, logo));
    }

    @Test
    void public_onlyNormalEnterprises_noPrivateFields_adminCreateAndExport() {
        Long pending = registered(username("pub_"));
        Long normal = registered(username("pub_"));
        adminService.approve(normal, ADMIN);

        List<Long> listed = publicService.list(new PageQuery(), null).getRecords().stream().map(EnterpriseVOs.Card::getId).toList();
        assertTrue(listed.contains(normal));
        assertFalse(listed.contains(pending), "待审核的不公开");
        assertMessage("企业不存在", () -> publicService.detail(pending));
        Set<String> fields = new HashSet<>();
        for (Field f : EnterpriseVOs.Card.class.getDeclaredFields()) {
            fields.add(f.getName());
        }
        assertEquals(Set.of("id", "name", "logoUrl", "address", "contactPhone", "intro"), fields,
                "公开展示不许长出信用代码、负责人、登录账号");

        EnterpriseDTOs.AdminCreate c = new EnterpriseDTOs.AdminCreate();
        c.setName("后台代建企业" + EnterpriseTestSupport.next());
        c.setCreditCode(creditCode());
        c.setLeaderName("代建负责人");
        String leaderPhone = phone();
        c.setLeaderPhone(leaderPhone);
        c.setUsername(username("adm_"));
        c.setPassword("init1234");
        Long created = adminService.create(c, ADMIN);
        EnterpriseVOs.Account a = adminService.detail(created);
        assertEquals(1, a.getStatus(), "后台代建直接正常");
        assertEquals(2, a.getSource());
        assertEquals(leaderPhone, a.getLeaderPhone());

        assertEquals(List.of(created), adminService.list(new PageQuery(), null, leaderPhone).getRecords().stream()
                .map(EnterpriseVOs.Account::getId).toList(), "按负责人完整手机号精确查");
        List<List<String>> rows = adminService.exportRows(null, c.getName());
        assertEquals(1, rows.size());
        assertEquals(EnterpriseAdminService.exportHead().size(), rows.get(0).size());
        assertEquals(leaderPhone, rows.get(0).get(6));
        assertTrue(adminService.list(new PageQuery(), 0, null).getRecords().stream().noneMatch(x -> x.getId().equals(created)));
    }

    @Test
    void passwordReset_needsUsernameAndLeaderPhone_andChangePasswordChecksOld() throws Exception {
        String user = username("pwd_");
        String p = phone();
        Long id = authService.register(register(user, creditCode(), p, code(p)));
        EnterpriseDTOs.ResetPassword reset = new EnterpriseDTOs.ResetPassword();
        reset.setUsername(user);
        reset.setPhone(phone());
        reset.setNewPassword("newpass1");
        assertMessage("该手机号未绑定企业账号", () -> authService.sendCode(reset.getPhone(), SmsScene.ENTERPRISE_PASSWORD_RESET, null));
        reset.setPhone(p);
        reset.setSmsCode(EnterpriseTestSupport.sendAndReadCode(verifyCodeService, redisUtil, p, SmsScene.ENTERPRISE_PASSWORD_RESET));
        reset.setUsername(username("other_"));
        assertMessage("账号与负责人手机号对不上", () -> authService.resetPassword(reset));
        reset.setUsername(user);
        reset.setSmsCode(EnterpriseTestSupport.sendAndReadCode(verifyCodeService, redisUtil, p, SmsScene.ENTERPRISE_PASSWORD_RESET));
        authService.resetPassword(reset);
        assertEquals(0, inRequest(() -> authService.login(login(user, "newpass1"), null)).getStatus());

        EnterpriseDTOs.ChangePassword change = new EnterpriseDTOs.ChangePassword();
        change.setOldPassword("wrong");
        change.setNewPassword("newpass2");
        assertMessage("原密码不正确", () -> authService.changePassword(id, change));
        change.setOldPassword("newpass1");
        authService.changePassword(id, change);
        assertMessage("账号或密码错误", () -> authService.login(login(user, "newpass1"), null));
        assertMessage("不支持的验证码场景", () -> authService.sendCode(p, "register", null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_account WHERE id = ?", Integer.class, id));
    }
}
