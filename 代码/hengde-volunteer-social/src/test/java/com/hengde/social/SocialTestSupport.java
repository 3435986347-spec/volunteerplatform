package com.hengde.social;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.social.dto.SocialDTOs;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区用例的公共造数。
 *
 * @author hengde
 */
abstract class SocialTestSupport {

    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    VolunteerMapper volunteerMapper;
    @Autowired
    AdminUserMapper adminUserMapper;
    @Autowired
    CryptoUtil cryptoUtil;

    /** 已实名、已验手机号的志愿者。 */
    Long member() {
        return volunteer(true, true, "社员" + SEQ.incrementAndGet());
    }

    Long volunteer(boolean registered, boolean verifiedPhone, String nick) {
        Volunteer v = new Volunteer();
        long n = SEQ.incrementAndGet();
        v.setOpenid("test:social:" + System.nanoTime() + ":" + n);
        v.setRealName("真实姓名" + n);
        v.setNickName(nick == null ? null : nick + "-" + n);
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setBirthday(LocalDate.of(2003, 5, 5));
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        if (verifiedPhone) {
            String phone = "136" + String.format("%08d", n % 100_000_000L);
            v.setPhone(cryptoUtil.encrypt(phone));
            v.setPhoneHash(cryptoUtil.hashPhone(phone));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    Long admin(String department) {
        AdminUser u = new AdminUser();
        u.setUsername("social_admin_" + SEQ.incrementAndGet());
        u.setPassword("x");
        u.setRealName(department + "负责人");
        u.setDepartment(department);
        u.setIsSuperAdmin(0);
        u.setStatus(0);
        adminUserMapper.insert(u);
        return u.getId();
    }

    /** 存储未启用时 upload 返回的占位 URL 形状（isOwnUpload 认它）。 */
    static String ownUrl(String dir, String ext) {
        return "[oss-disabled]/" + dir + "/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    static SocialDTOs.PostSave text(String content) {
        SocialDTOs.PostSave d = new SocialDTOs.PostSave();
        d.setContent(content);
        return d;
    }

    static SocialDTOs.PostSave withVisibility(String content, int visibility) {
        SocialDTOs.PostSave d = text(content);
        d.setVisibility(visibility);
        return d;
    }

    static SocialDTOs.CommentSave commentOf(String content, Long parentId) {
        SocialDTOs.CommentSave c = new SocialDTOs.CommentSave();
        c.setContent(content);
        c.setParentId(parentId);
        return c;
    }

    static PageQuery page(int size) {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(size);
        return q;
    }

    static List<String> images(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> ownUrl("social", "jpg")).toList();
    }

    static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
