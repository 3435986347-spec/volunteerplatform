package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.support.QuestionType;
import com.hengde.user.dto.AddressSaveDTO;
import com.hengde.user.dto.CenterContentSaveDTO;
import com.hengde.user.service.AddressService;
import com.hengde.user.service.CenterContentService;
import com.hengde.user.service.MyProfileService;
import com.hengde.user.vo.AddressVO;
import com.hengde.user.vo.CenterContentVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人中心补全（V4 个人中心补全批）：地址管理（Row 40）、我的保险 / 联系客服（Row 42 / 48）、
 * 手写签名板与意见反馈（Row 48）。年级升级与订阅通知另见各自的用例。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PersonalCenterTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    private AddressService addressService;
    @Autowired
    private CenterContentService contentService;
    @Autowired
    private MyProfileService profileService;
    @Autowired
    private FormService formService;
    @Autowired
    private FormSubmissionService submissionService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void addresses_crudTopUniquenessAndOwnership() {
        Long me = volunteer();
        Long a = addressService.create(me, address("张三", "13800000001", "广东省湛江市雷州市", "新城大道 1 号"));
        Long b = addressService.create(me, address("李四", "13800000002", "广东省湛江市雷州市", "西湖大道 2 号"));
        assertNotEquals("13800000001", jdbc.queryForObject("SELECT recv_phone FROM user_address WHERE id = ?",
                String.class, a), "收件电话落库是密文");

        addressService.top(me, a);
        addressService.top(me, b);
        List<AddressVO> list = addressService.list(me);
        assertEquals(b, list.get(0).getId(), "置顶的在最前");
        assertTrue(list.get(0).getTop());
        assertFalse(list.stream().filter(x -> x.getId().equals(a)).findFirst().orElseThrow().getTop(),
                "置顶新的会取消旧的：一人至多一条");
        assertEquals("13800000002", list.get(0).getRecvPhone(), "本人看到明文");
        addressService.top(me, b);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE volunteer_id = ? AND is_top = 1 "
                + "AND is_deleted = 0", Integer.class, me), "再点一次置顶不报错也不多出一条");

        addressService.update(me, a, address("张三丰", "13800000003", "广东省湛江市雷州市", "新城大道 3 号"));
        assertEquals("张三丰", addressService.list(me).stream().filter(x -> x.getId().equals(a)).findFirst()
                .orElseThrow().getRecvName());
        addressService.untop(me, b);
        assertTrue(addressService.list(me).stream().noneMatch(AddressVO::getTop));

        Long other = volunteer();
        assertMessage("地址不存在", () -> addressService.update(other, a, address("x", "13800000004", "y", "z")));
        assertMessage("地址不存在", () -> addressService.delete(other, a));
        assertMessage("地址不存在", () -> addressService.top(other, a));
        assertMessage("收件电话格式不正确", () -> addressService.create(me, address("x", "12345", "y", "z")));

        addressService.delete(me, a);
        assertEquals(List.of(b), addressService.list(me).stream().map(AddressVO::getId).toList());
        for (int i = addressService.list(me).size(); i < AddressService.MAX_PER_VOLUNTEER; i++) {
            addressService.create(me, address("批量" + i, "13800000009", "地区", "门牌 " + i));
        }
        assertMessage("最多保存 20 个地址", () -> addressService.create(me, address("超", "13800000009", "地区", "门牌")));
    }

    @Test
    void centerContent_fixedKeys_ownImages_andWholeReplacement() {
        String key = CenterContentService.CUSTOMER_SERVICE;
        jdbc.update("DELETE FROM user_center_content WHERE content_key = ?", key);
        assertNull(contentService.get(key), "还没设置：给空，不是错误");
        assertMessage("内容只能是 insurance（我的保险）/ customer-service（联系客服）",
                () -> contentService.get("anything"));

        CenterContentSaveDTO withExternal = content("联系客服", "电话 0759-1234567", List.of("https://evil.example.com/qr.png"));
        assertMessage("图片无效，请重新上传", () -> contentService.save(key, withExternal, 1L));
        String qr = "[oss-disabled]/center/20260917/" + "c".repeat(32) + ".png";
        contentService.save(key, content("联系客服", "电话 0759-1234567", List.of(qr)), 1L);
        contentService.save(key, content("联系客服（新）", "电话 0759-7654321", null), 2L);
        CenterContentVO vo = contentService.get(key);
        assertEquals("联系客服（新）", vo.getTitle());
        assertTrue(vo.getImages().isEmpty(), "整份替换：这次没传图片就是清掉图片");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_center_content WHERE content_key = ?",
                Integer.class, key), "同一个键只有一行");
        assertMessage("标题、正文、图片至少填一项", () -> contentService.save(key, content(null, " ", null), 1L));
    }

    @Test
    void padSignature_ownUploadOnly_andLeavesTheAgreementSignatureAlone() {
        Long me = volunteer();
        jdbc.update("UPDATE volunteer SET signature_url = 'agreement-signature' WHERE id = ?", me);
        assertMessage("签名图片无效，请重新上传", () -> profileService.setPadSignature(me, "https://evil.example.com/s.png"));
        String own = "[oss-disabled]/signature/20260917/" + "d".repeat(32) + ".png";
        profileService.setPadSignature(me, own);
        assertEquals(own, profileService.getMyProfile(me).getPadSignatureUrl());
        assertEquals("agreement-signature", jdbc.queryForObject("SELECT signature_url FROM volunteer WHERE id = ?",
                String.class, me), "注册协议签名是留痕，不被签名板覆盖");
        profileService.clearPadSignature(me);
        assertNull(profileService.getMyProfile(me).getPadSignatureUrl());
    }

    @Test
    void feedbackForm_directlySubmittable_multipleTimes_butNotListedAsAQuestionnaire() {
        jdbc.update("UPDATE org_form SET status = 2 WHERE scene = ? AND status = 1", FormFlow.SCENE_FEEDBACK);
        FormDTOs.Save d = new FormDTOs.Save();
        d.setScene(FormFlow.SCENE_FEEDBACK);
        d.setTitle("意见反馈-" + SEQ.incrementAndGet());
        FormDTOs.QuestionSave q = new FormDTOs.QuestionSave();
        q.setType(QuestionType.TEXT);
        q.setTitle("您的意见");
        d.setQuestions(List.of(q));
        Long formId = formService.create(d, 1L);
        formService.publish(formId, 1L);
        assertFalse(formService.detail(formId).getSingleSubmit(), "意见反馈不传「每人一次」时默认不限次数");

        Long me = volunteer();
        Long questionId = submissionService.currentForScene(FormFlow.SCENE_FEEDBACK, me).getQuestions().get(0).getId();
        FormDTOs.Answer a = new FormDTOs.Answer();
        a.setQuestionId(questionId);
        a.setValue("报名页面加载慢");
        FormDTOs.Submit s = new FormDTOs.Submit();
        s.setAnswers(List.of(a));
        submissionService.submit(formId, me, s);
        submissionService.submit(formId, me, s);
        assertEquals(2, submissionService.mySubmissions(formId, me).size());
        PageQuery page = new PageQuery();
        page.setPage(1);
        page.setSize(100);
        assertFalse(submissionService.listAvailable(me, page).getRecords().stream().anyMatch(f -> f.getId().equals(formId)),
                "意见反馈的入口在安全中心，不混进问卷列表");
        formService.close(formId);
    }

    // ---------------- helpers ----------------

    private Long volunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("test:center:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("个人中心" + SEQ.get());
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setRegisterTime(java.time.LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private static AddressSaveDTO address(String name, String phone, String region, String detail) {
        AddressSaveDTO d = new AddressSaveDTO();
        d.setRecvName(name);
        d.setRecvPhone(phone);
        d.setRegion(region);
        d.setDetail(detail);
        return d;
    }

    private static CenterContentSaveDTO content(String title, String body, List<String> images) {
        CenterContentSaveDTO d = new CenterContentSaveDTO();
        d.setTitle(title);
        d.setBody(body);
        d.setImages(images);
        return d;
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).getMessage());
    }
}
