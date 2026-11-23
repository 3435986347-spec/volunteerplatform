package com.hengde.api.init;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.AuthProperties;
import com.hengde.auth.dao.AdminUserMapper;
import com.hengde.auth.entity.AdminUser;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.utils.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 超级管理员初始化：启动时若库中没有任何超管，则按配置预置一个。
 *
 * <p>放在 api（唯一可部署单元、Flyway 已建好表）而非 auth，避免领域模块测试上下文触发它而缺表。
 * 运行时用 {@link PasswordUtil} 加密，密码来自配置，不硬编码哈希。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SuperAdminInitializer implements ApplicationRunner {

    private AdminUserMapper adminUserMapper;
    private AuthProperties authProperties;

    @Autowired
    public void setAdminUserMapper(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!authProperties.isInitSuperAdmin()) {
            return;
        }
        Long count = adminUserMapper.selectCount(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getIsSuperAdmin, 1));
        if (count != null && count > 0) {
            return;
        }
        AdminUser admin = new AdminUser();
        admin.setUsername(authProperties.getSuperAdminUsername());
        admin.setPassword(PasswordUtil.encrypt(authProperties.getSuperAdminPassword()));
        admin.setRealName("超级管理员");
        admin.setIsSuperAdmin(1);
        admin.setStatus(UserStatus.NORMAL);
        adminUserMapper.insert(admin);
        log.warn("已初始化超级管理员账号 [{}]，请尽快登录修改默认密码", authProperties.getSuperAdminUsername());
    }
}
