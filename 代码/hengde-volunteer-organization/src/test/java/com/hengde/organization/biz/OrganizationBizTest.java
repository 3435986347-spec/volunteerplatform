package com.hengde.organization.biz;

import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.service.SquadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfig.class)
class OrganizationBizTest {

    private OrganizationStructureNodeMapper nodeMapper;
    private VolunteerSquadMapper squadMapper;
    private SquadService squadService;

    @Autowired
    public void setNodeMapper(OrganizationStructureNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @Autowired
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    @Autowired
    public void setSquadService(SquadService squadService) {
        this.squadService = squadService;
    }

    @Test
    void organizationMigrationCreatesSeededStructure() {
        assertTrue(nodeMapper.selectCount(null) >= 4);
    }

    @Test
    void canCreateSquad() {
        SquadDTO dto = new SquadDTO();
        dto.setName("测试分队");
        dto.setType("学校");
        dto.setLeaderName("负责人");
        dto.setLeaderPhone("13800000000");

        Long id = squadService.create(dto);

        assertNotNull(id);
        assertNotNull(squadMapper.selectById(id));
    }
}
