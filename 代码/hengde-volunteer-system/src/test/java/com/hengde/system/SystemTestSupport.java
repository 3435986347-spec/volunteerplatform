package com.hengde.system;

import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统治理用例的公共造数。
 *
 * @author hengde
 */
abstract class SystemTestSupport {

    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    AdminUserMapper adminUserMapper;

    /** 普通后台账号。 */
    Long admin(String department) {
        return admin(department, false);
    }

    Long superAdmin() {
        return admin("理事会", true);
    }

    Long admin(String department, boolean superAdmin) {
        long n = SEQ.incrementAndGet();
        AdminUser u = new AdminUser();
        u.setUsername("sys_admin_" + n);
        u.setPassword("x");
        // ⚠️ 姓名里不要带部门名：水印用例要断言「关掉部门之后文字里没有部门」，姓名带了就分不清是哪一项拼进去的
        u.setRealName("张三" + n);
        u.setPhone("139" + String.format("%08d", n % 100_000_000L));
        u.setDepartment(department);
        u.setIsSuperAdmin(superAdmin ? 1 : 0);
        u.setStatus(0);
        adminUserMapper.insert(u);
        return u.getId();
    }

    /** 存储未启用时 upload 返回的占位 URL 形状（isOwnUpload 认它）。 */
    static String ownUrl(String dir, String ext) {
        return "[oss-disabled]/" + dir + "/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
    }

    static PageQuery page(int size) {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(size);
        return q;
    }

    static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
