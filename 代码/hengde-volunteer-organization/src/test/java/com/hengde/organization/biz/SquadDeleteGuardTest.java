package com.hengde.organization.biz;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.service.SquadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除分队守卫（A2）：仍有成员（volunteer.squad_id 指向该分队）的分队不可物理删除，避免悬挂引用；
 * 无成员的分队正常删除。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SquadDeleteGuardTest {

    @Autowired
    private SquadService squadService;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void delete_rejectedWhenSquadStillHasMembers() {
        Long squadId = insertSquad("带成员的分队");
        insertVolunteerInSquad(squadId);

        BusinessException ex = assertThrows(BusinessException.class, () -> squadService.delete(squadId));
        assertTrue(ex.getMessage().contains("仍有成员"));
        assertNotNull(squadMapper.selectById(squadId), "拒删后分队应仍在");
    }

    @Test
    void delete_succeedsWhenNoMembers() {
        Long squadId = insertSquad("空分队");

        squadService.delete(squadId);
        assertNull(squadMapper.selectById(squadId), "无成员分队应被删除");
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

    private Long insertVolunteerInSquad(Long squadId) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("分队成员");
        v.setStatus(0);
        v.setSquadId(squadId);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
