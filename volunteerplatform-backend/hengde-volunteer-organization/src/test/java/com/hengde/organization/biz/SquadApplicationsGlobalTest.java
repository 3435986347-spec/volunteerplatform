package com.hengde.organization.biz;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.SquadApplicationMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.SquadApplication;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局分队加入申请（不按分队 ID）验证：{@code SquadService.applications(PageQuery, status)}。
 *
 * <p>这是为后台「待审分队加入」待办卡片新增的全局入口——此前只有按分队的 {@code applications(squadId, query)}。
 * 断言 records 内容而非 total（领域模块测试上下文无分页拦截器）。<b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SquadApplicationsGlobalTest {

    private static final int PENDING = 0;
    private static final int APPROVED = 1;

    @Autowired
    private SquadService squadService;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private SquadApplicationMapper applicationMapper;

    @Test
    void globalApplications_defaultsToPendingAcrossSquads_withSquadName() {
        Long squadA = insertSquad("甲分队");
        Long squadB = insertSquad("乙分队");
        long pendingA = insertApplication(squadA, PENDING);
        long pendingB = insertApplication(squadB, PENDING);
        insertApplication(squadA, APPROVED); // 已通过——默认查询应排除

        PageResult<SquadApplicationVO> page = squadService.applications(new PageQuery(), null);
        List<SquadApplicationVO> records = page.getRecords();

        // 两条待审都在，已通过的不在（断言这三条本测试造的行的归属，不假设全库无其他数据）
        assertTrue(records.stream().anyMatch(v -> v.getId() == pendingA), "甲分队待审应在列表");
        assertTrue(records.stream().anyMatch(v -> v.getId() == pendingB), "乙分队待审应在列表");
        assertTrue(records.stream().allMatch(v -> PENDING == v.getStatus()), "默认仅返回待审（status=0）");
        // 跨分队识别：每行带 squadName
        SquadApplicationVO rowA = records.stream().filter(v -> v.getId() == pendingA).findFirst().orElseThrow();
        assertEquals("甲分队", rowA.getSquadName(), "全局列表应回填分队名");
    }

    @Test
    void globalApplications_explicitStatusOverridesDefault() {
        Long squad = insertSquad("丙分队");
        long approved = insertApplication(squad, APPROVED);
        insertApplication(squad, PENDING);

        List<SquadApplicationVO> records = squadService.applications(new PageQuery(), APPROVED).getRecords();

        assertTrue(records.stream().anyMatch(v -> v.getId() == approved), "传 status=1 应能查到已通过");
        assertTrue(records.stream().allMatch(v -> APPROVED == v.getStatus()), "显式 status 覆盖默认待审");
    }

    private Long insertSquad(String name) {
        VolunteerSquad squad = new VolunteerSquad();
        squad.setName(name);
        squad.setType("学校");
        squad.setStatus(1);
        squad.setMemberLimit(0);
        squadMapper.insert(squad);
        return squad.getId();
    }

    private long insertApplication(Long squadId, int status) {
        SquadApplication app = new SquadApplication();
        app.setSquadId(squadId);
        app.setVolunteerId(9000L + squadId); // 任意，本测试不校验志愿者存在
        app.setStatus(status);
        app.setApplyTime(LocalDateTime.now());
        applicationMapper.insert(app);
        return app.getId();
    }
}
