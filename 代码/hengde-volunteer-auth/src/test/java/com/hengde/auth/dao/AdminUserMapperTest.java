package com.hengde.auth.dao;

import com.hengde.auth.entity.AdminUser;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 演示并验证：领域模块的 mapper 级 DB 测试能拿到 schema。
 *
 * <p>schema 来自 common 的 {@code db/migration}，由 Flyway（同样经 common 传递）在
 * {@link TestcontainersConfig} 起的 MySQL 容器里建表，因此这里能直接对真实 {@code admin_user} 表增删查。
 * <b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class AdminUserMapperTest {

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Test
    void insertAndSelect() {
        AdminUser admin = new AdminUser();
        admin.setUsername("test_" + System.currentTimeMillis());
        admin.setPassword("encrypted");
        admin.setRealName("测试管理员");
        admin.setIsSuperAdmin(0);
        admin.setStatus(0);

        adminUserMapper.insert(admin);
        assertNotNull(admin.getId(), "插入后应回填自增主键");

        AdminUser found = adminUserMapper.selectById(admin.getId());
        assertNotNull(found, "应能按 id 查回");
        assertEquals(admin.getUsername(), found.getUsername());
    }
}
