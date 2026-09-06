package com.hengde.honor;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.constant.RoleModelLinkType;
import com.hengde.honor.constant.RoleModelType;
import com.hengde.honor.dto.RoleModelSaveDTO;
import com.hengde.honor.service.RoleModelService;
import com.hengde.honor.vo.RoleModelVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    public void setRoleModelService(RoleModelService roleModelService) {
        this.roleModelService = roleModelService;
    }

    @Autowired
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        // V43 起：填了链接就必须一起说清用哪种方式打开，光给地址会被拒
        dto.setLinkType(RoleModelLinkType.WEB);
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

    // ---------- V43：类型 / 简介 / 跳转 / 发布时间 ----------

    /** 留空按「个人」：后台旧表单不认识这个字段，落 null 会撞 NOT NULL。 */
    @Test
    void modelType_defaultsToPerson_andRejectsInvalid() {
        Long id = roleModelService.create(dto("默认个人"));
        assertEquals(RoleModelType.PERSON, find(id).getModelType());
        assertEquals("个人", find(id).getModelTypeLabel());

        RoleModelSaveDTO bad = dto("非法类型");
        bad.setModelType(9);
        assertThrows(BusinessException.class, () -> roleModelService.create(bad));
    }

    /** 小程序按个人/团队分页签，筛错了这条就出现在另一个页签里。 */
    @Test
    void listPublished_filtersByModelType() {
        Long person = publish(withType(dto("个人榜样"), RoleModelType.PERSON));
        Long team = publish(withType(dto("团队榜样"), RoleModelType.TEAM));

        assertTrue(idsOf(query(null, RoleModelType.PERSON, null)).contains(person));
        assertFalse(idsOf(query(null, RoleModelType.PERSON, null)).contains(team));
        assertTrue(idsOf(query(null, RoleModelType.TEAM, null)).contains(team));
        assertThrows(BusinessException.class, () -> query(null, 9, null));
    }

    /** 关键词要能搜到简介——简介是卡片正文，运营写的关键信息多半在那儿而不在标题里。 */
    @Test
    void listPublished_keywordMatchesSummary() {
        RoleModelSaveDTO d = dto("关键词");
        d.setSummary("他在台风天转移了三十位老人");
        Long id = publish(d);

        assertTrue(idsOf(query("台风天", null, null)).contains(id), "简介要参与关键词匹配");
        assertFalse(idsOf(query("完全无关的词", null, null)).contains(id));
    }

    /**
     * 未知的 sort 直接报错，不悄悄按默认排。
     *
     * <p>拼错一个值就得到另一种顺序、且没有任何提示，是最难查的那类问题——
     * 尤其它「看起来还工作」。</p>
     */
    @Test
    void listPublished_rejectsUnknownSort() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> query(null, null, "publishTimeDesc"));
        assertTrue(ex.getMessage().contains("排序方式非法"), "实际：" + ex.getMessage());
        query(null, null, RoleModelService.SORT_LATEST);
        query(null, null, RoleModelService.SORT_DEFAULT);
    }

    /**
     * 发布时间＝首次上架那一刻：下架不清、再次上架不覆盖、改内容也不覆盖。
     *
     * <p>否则临时下架修个错别字再上架，它就会在小程序的「最新」里跳到最前面。</p>
     *
     * <p>⚠️ <b>必须把首次发布时间改成一个明显久远的值再验后续步骤</b>，不能直接比
     * 「上架 → 再上架」两次的读数：{@code publish_time} 是 {@code DATETIME}（精度到秒），
     * 整个用例跑完不到一秒，<b>两次写入落在同一秒里、值完全相等</b>，
     * 于是「每次上架都重写」这种错误实现照样全绿。
     * 第一版就是这么写的，变异验证当场照出来了（改成每次都写 → 仍然绿）。
     * 同一个坑 {@code effective_time} 记过一次（MySQL 写 DATETIME 是四舍五入）。</p>
     */
    @Test
    void publishTime_isWrittenOnceOnFirstOnShelf() {
        Long id = roleModelService.create(dto("发布时间"));
        assertNull(find(id).getPublishTime(), "没上架过就没有发布时间");

        roleModelService.changeStatus(id, RoleModelService.ON_SHELF);
        assertNotNull(find(id).getPublishTime(), "首次上架必须写发布时间");

        // 拉开到一个不可能与"现在"落在同一秒的时刻，后面三步才有分辨力
        LocalDateTime old = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        jdbcTemplate.update("UPDATE honor_role_model SET publish_time = ? WHERE id = ?", old, id);
        assertEquals(old, find(id).getPublishTime(), "用例自检：改写应生效");

        roleModelService.changeStatus(id, RoleModelService.OFF_SHELF);
        assertEquals(old, find(id).getPublishTime(), "下架不该清掉发布时间");

        roleModelService.update(id, withType(dto("改个错别字"), RoleModelType.PERSON));
        assertEquals(old, find(id).getPublishTime(), "改内容不该顺带把发布时间改成今天");

        roleModelService.changeStatus(id, RoleModelService.ON_SHELF);
        assertEquals(old, find(id).getPublishTime(),
                "再次上架不该覆盖首次发布时间——否则下架改个错别字再上架就跳到「最新」最前面");
    }

    /** 选了跳转就必须给链接，否则小程序拿到一个空地址去开 WebView。 */
    @Test
    void link_requiresUrlWhenTypeIsNotNone() {
        RoleModelSaveDTO d = dto("缺链接");
        d.setLinkType(RoleModelLinkType.WEB);
        d.setLinkUrl(null);
        assertThrows(BusinessException.class, () -> roleModelService.create(d));
    }

    /** WEB 必须 https：http 在体验版能开、正式版白屏，属于「上线才发现」，故保存时就拦。 */
    @Test
    void webLink_mustBeHttps() {
        RoleModelSaveDTO d = dto("http 网页");
        d.setLinkType(RoleModelLinkType.WEB);
        d.setLinkUrl("http://example.com/a");
        BusinessException ex = assertThrows(BusinessException.class, () -> roleModelService.create(d));
        assertTrue(ex.getMessage().contains("https"), "实际：" + ex.getMessage());

        // 外部链接（只能复制）不受此限——它本来就不进 WebView
        RoleModelSaveDTO ok = dto("http 外链");
        ok.setLinkType(RoleModelLinkType.EXTERNAL);
        ok.setLinkUrl("http://example.com/a");
        Long id = roleModelService.create(ok);
        assertEquals("EXTERNAL", find(id).getLinkTypeName());
    }

    /**
     * 「不跳转」却填了链接：报错，<b>不静默清空</b>。
     *
     * <p>静默清空是最糟的一种——运营明明填了地址、保存也提示成功，链接却没了。</p>
     */
    @Test
    void noneLinkWithUrl_isRejectedNotSilentlyCleared() {
        RoleModelSaveDTO d = dto("矛盾的跳转");
        d.setLinkType(RoleModelLinkType.NONE);
        d.setLinkUrl("https://example.com/a");
        assertThrows(BusinessException.class, () -> roleModelService.create(d));
    }

    /** 出参同时给码值和稳定英文名，客户端直接 switch 名字即可。 */
    @Test
    void linkTypeName_isDownstreamStable() {
        Long none = roleModelService.create(dto("无跳转"));
        assertEquals("NONE", find(none).getLinkTypeName());

        RoleModelSaveDTO page = dto("内部页面");
        page.setLinkType(RoleModelLinkType.PAGE);
        page.setLinkUrl("/pages/activity/detail?id=12");
        assertEquals("PAGE", find(roleModelService.create(page)).getLinkTypeName());
    }

    // ---------- helpers ----------

    private RoleModelSaveDTO withType(RoleModelSaveDTO d, int type) {
        d.setModelType(type);
        return d;
    }

    private Long publish(RoleModelSaveDTO d) {
        Long id = roleModelService.create(d);
        roleModelService.changeStatus(id, RoleModelService.ON_SHELF);
        return id;
    }

    /**
     * 志愿者端查询。
     *
     * <p><b>size 放大到 500 并按 id 断言、不断言 total</b>：领域模块测试上下文没有分页拦截器
     * （它只装配在 api），selectPage 不加 LIMIT、total 恒为 0；而榜样列表是全库的，
     * 别的用例造的数据也在里面。</p>
     */
    private PageResult<RoleModelVO> query(String keyword, Integer type, String sort) {
        PageQuery q = new PageQuery();
        q.setSize(500);
        return roleModelService.listPublished(q, keyword, type, sort);
    }

    private java.util.Set<Long> idsOf(PageResult<RoleModelVO> page) {
        return page.getRecords().stream().map(RoleModelVO::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private RoleModelVO find(Long id) {
        return roleModelService.listForAdmin(null).stream()
                .filter(v -> id.equals(v.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("榜样应存在：" + id));
    }

    private boolean publishedContains(Long id) {
        return idsOf(query(null, null, null)).contains(id);
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
