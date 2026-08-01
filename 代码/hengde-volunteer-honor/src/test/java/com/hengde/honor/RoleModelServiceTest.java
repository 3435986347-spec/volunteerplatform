package com.hengde.honor;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.dto.RoleModelSaveDTO;
import com.hengde.honor.service.RoleModelService;
import com.hengde.honor.vo.RoleModelVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 榜样验证。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p>断言按 id 过滤而非按列表长度——榜样列表是全库的，别的用例造的数据也在里面。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class RoleModelServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private RoleModelService roleModelService;

    @Autowired
    public void setRoleModelService(RoleModelService roleModelService) {
        this.roleModelService = roleModelService;
    }

    @Test
    void create_landsOffShelf_andIsInvisibleToVolunteer() {
        Long id = roleModelService.create(dto("新建下架"));

        assertEquals(RoleModelService.OFF_SHELF, find(id).getStatus(),
                "新建即上架会让还没填完图片的条目直接出现在志愿者端");
        assertFalse(publishedContains(id), "下架态志愿者端不可见");
    }

    @Test
    void onShelf_makesItVisible() {
        Long id = roleModelService.create(dto("上架可见"));
        roleModelService.changeStatus(id, RoleModelService.ON_SHELF);

        assertTrue(publishedContains(id));

        roleModelService.changeStatus(id, RoleModelService.OFF_SHELF);
        assertFalse(publishedContains(id), "下架后应立即不可见");
    }

    @Test
    void invalidStatus_isRejected() {
        Long id = roleModelService.create(dto("非法状态"));
        assertThrows(BusinessException.class, () -> roleModelService.changeStatus(id, 9));
        assertThrows(BusinessException.class, () -> roleModelService.changeStatus(id, null));
    }

    @Test
    void update_changesFields() {
        Long id = roleModelService.create(dto("改前"));
        RoleModelSaveDTO dto = dto("改后");
        dto.setSubtitle("新的副标题");
        dto.setLinkUrl("https://example.com/article");
        roleModelService.update(id, dto);

        RoleModelVO vo = find(id);
        assertEquals("新的副标题", vo.getSubtitle());
        assertEquals("https://example.com/article", vo.getLinkUrl());
    }

    /** 副标题等可选字段要能被清空——MP 的 updateById 默认跳过 null，直觉写法会让旧值留着。 */
    @Test
    void update_canClearOptionalFields() {
        Long id = roleModelService.create(dto("清空可选字段"));
        RoleModelSaveDTO cleared = dto("清空可选字段");
        cleared.setSubtitle(null);
        cleared.setImageUrl(null);
        cleared.setLinkUrl(null);

        roleModelService.update(id, cleared);

        RoleModelVO vo = find(id);
        assertNull(vo.getSubtitle(), "删掉副标题保存后它不该还在");
        assertNull(vo.getImageUrl());
        assertNull(vo.getLinkUrl());
    }

    @Test
    void update_doesNotChangeShelfStatus() {
        Long id = roleModelService.create(dto("改不动上下架"));
        roleModelService.changeStatus(id, RoleModelService.ON_SHELF);

        roleModelService.update(id, dto("改内容"));

        assertEquals(RoleModelService.ON_SHELF, find(id).getStatus(),
                "改内容不该顺带把上架状态改掉");
    }

    @Test
    void updateSort_works() {
        Long id = roleModelService.create(dto("排序"));
        roleModelService.updateSort(id, 7);
        assertEquals(7, find(id).getSort());
        assertThrows(BusinessException.class, () -> roleModelService.updateSort(id, null));
    }

    @Test
    void delete_removesIt() {
        Long id = roleModelService.create(dto("删除"));
        roleModelService.delete(id);

        assertTrue(roleModelService.listForAdmin(null).stream().noneMatch(v -> id.equals(v.getId())));
        assertThrows(BusinessException.class, () -> roleModelService.delete(id), "重复删除应报不存在");
    }

    @Test
    void listForAdmin_filtersByStatus() {
        Long off = roleModelService.create(dto("筛选下架"));
        Long on = roleModelService.create(dto("筛选上架"));
        roleModelService.changeStatus(on, RoleModelService.ON_SHELF);

        assertTrue(roleModelService.listForAdmin(RoleModelService.OFF_SHELF).stream()
                .anyMatch(v -> off.equals(v.getId())));
        assertTrue(roleModelService.listForAdmin(RoleModelService.OFF_SHELF).stream()
                .noneMatch(v -> on.equals(v.getId())));
    }

    // ---------- helpers ----------

    private RoleModelVO find(Long id) {
        return roleModelService.listForAdmin(null).stream()
                .filter(v -> id.equals(v.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("榜样应存在：" + id));
    }

    private boolean publishedContains(Long id) {
        return roleModelService.listPublished().stream().anyMatch(v -> id.equals(v.getId()));
    }

    private RoleModelSaveDTO dto(String title) {
        RoleModelSaveDTO dto = new RoleModelSaveDTO();
        dto.setTitle(title + "_" + SEQ.incrementAndGet());
        dto.setSubtitle("副标题");
        dto.setImageUrl("https://example.com/role-model.png");
        dto.setSort(0);
        return dto;
    }
}
