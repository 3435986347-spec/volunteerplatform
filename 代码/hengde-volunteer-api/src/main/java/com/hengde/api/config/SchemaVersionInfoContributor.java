package com.hengde.api.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/actuator/info} 里报出数据库迁移到了哪一版（与构建版本、构建时间一起）。
 *
 * <p>起因是小程序侧 2026-10 的联调报告：一批接口在线上 500，前端没法判断线上跑的是哪个包、
 * 迁移有没有执行，只能等后端上服务器看日志。实际上那次是线上还跑着 V4 之前的旧包。
 * 有了这两个数，<b>不登录、不看日志</b>就能判断：构建时间早于某次发版、或迁移版本低于代码里最新的那个号，
 * 就是没部署到位。</p>
 *
 * <p><b>只给版本号这一个数</b>，不给迁移清单、描述、执行时间与数据库连接信息——这个端点是公网可达的，
 * 报告里也明确说了「不要求在公网开放敏感配置端点」。</p>
 *
 * @author hengde
 */
@Component
public class SchemaVersionInfoContributor implements InfoContributor {

    private ObjectProvider<Flyway> flyway;

    @Autowired
    public void setFlyway(ObjectProvider<Flyway> flyway) {
        this.flyway = flyway;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> db = new LinkedHashMap<>();
        Flyway f = flyway.getIfAvailable();
        if (f == null) {
            db.put("schemaVersion", null);
            db.put("note", "Flyway 未启用");
        } else {
            try {
                MigrationInfo current = f.info().current();
                db.put("schemaVersion", current == null || current.getVersion() == null
                        ? null : current.getVersion().getVersion());
            } catch (RuntimeException e) {
                // 报不出来就如实说报不出来，不让 info 端点因此 500
                db.put("schemaVersion", null);
                db.put("note", "读取迁移记录失败");
            }
        }
        builder.withDetail("database", db);
    }
}
