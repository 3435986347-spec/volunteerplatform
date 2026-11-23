package com.hengde.system;

import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.service.FileVaultService;
import com.hengde.system.service.OperationLogService;
import com.hengde.system.service.SerialNumberService;
import com.hengde.system.service.SystemConfigService;
import com.hengde.system.support.OperationLogRecorder;
import com.hengde.system.vo.SystemVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统治理（V4 系统治理批，V83）：操作日志、界面水印、菜单排序、十位编号、文件网盘与开放窗口、分享链接。
 *
 * <p><b>需本机 Docker</b>（MySQL）。日志落库是异步的，用例直接调 {@code flush()}——这反而让断言是确定性的。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SystemGovernanceTest extends SystemTestSupport {

    @Autowired
    private OperationLogService logService;
    @Autowired
    private OperationLogRecorder recorder;
    @Autowired
    private SystemConfigService configService;
    @Autowired
    private SerialNumberService serialNumberService;
    @Autowired
    private FileVaultService vaultService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void operationLog_isAppendOnly_asyncAndSearchable() {
        Long adminId = admin("监察部");
        String tag = "日志用例" + SEQ.incrementAndGet();

        recorder.recordOperation(null, SystemCodes.ACTOR_ADMIN, adminId, tag + "：删除了一条公告", true, null, 12);
        recorder.recordOperation(null, SystemCodes.ACTOR_ADMIN, adminId, tag + "：越权的尝试", false, "没有权限", 3);
        assertTrue(logService.pending() >= 2, "先入队，不在请求线程上落库");
        assertEquals(2, logService.flush(), "flush 才写库");

        List<SystemVOs.OperationLog> rows = logService.search(SystemCodes.LOG_OPERATION, null, adminId, tag,
                null, null, page(20)).getRecords();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> !r.isSuccess() && "没有权限".equals(r.getErrorMsg())),
                "失败的也要记——被拒绝的越权尝试恰恰是追责时要看的");
        assertEquals("监察部", rows.get(0).getDepartment(), "部门存的是快照");
        assertNotNull(rows.get(0).getActorName());

        // 页面访问是另一类，能分开查（Row 62「谁点击了哪个页面」）
        recorder.recordPageView(null, adminId, tag + " 页面", "/overview");
        logService.flush();
        assertEquals(1, logService.search(SystemCodes.LOG_PAGE_VIEW, null, adminId, tag, null, null, page(20))
                .getRecords().size());
        assertEquals(2, logService.search(SystemCodes.LOG_OPERATION, null, adminId, tag, null, null, page(20))
                .getRecords().size());

        // 时间区间（右开）
        assertEquals(0, logService.search(null, null, adminId, tag, LocalDateTime.now().plusMinutes(1), null, page(20))
                .getRecords().size());
    }

    @Test
    void watermark_menuOrder_andSerials() {
        Long adminId = admin("宣传部");

        // ---- 水印：默认开着，文字由后端按这个人算（Row 78：姓名 / 部门 / 手机尾号）----
        SystemVOs.Watermark def = configService.watermark(adminId);
        assertTrue(def.isEnabled());
        String phone = jdbc.queryForObject("SELECT phone FROM admin_user WHERE id = ?", String.class, adminId);
        assertTrue(def.getText().contains("宣传部"), "水印带部门：" + def.getText());
        assertTrue(def.getText().contains(phone.substring(phone.length() - 4)), "水印带手机尾号");
        assertFalse(def.getText().contains(phone), "只给后四位，不是整串号码");

        SystemDTOs.WatermarkSave save = new SystemDTOs.WatermarkSave();
        save.setShowPhoneTail(false);
        save.setShowDepartment(false);
        save.setOpacityPercent(20);
        configService.saveWatermark(save, adminId);
        SystemVOs.Watermark changed = configService.watermark(adminId);
        assertEquals(20, changed.getOpacityPercent());
        assertFalse(changed.getText().contains("宣传部"), "关掉部门就不再拼进去");
        assertTrue(changed.isShowName(), "没传的开关保持原样");

        // 关掉水印之后连文字都不给（前端拿不到就画不出来）
        SystemDTOs.WatermarkSave off = new SystemDTOs.WatermarkSave();
        off.setEnabled(false);
        configService.saveWatermark(off, adminId);
        assertNull(configService.watermark(adminId).getText());
        SystemDTOs.WatermarkSave on = new SystemDTOs.WatermarkSave();
        on.setEnabled(true);
        configService.saveWatermark(on, adminId);

        // ---- 菜单排序：整份覆盖，按 sort 出；键重复直接拒 ----
        SystemDTOs.MenuOrderSave menus = new SystemDTOs.MenuOrderSave();
        menus.setItems(List.of(menuItem("activity", "活动", 20, true), menuItem("overview", "概览", 10, true),
                menuItem("logs", "操作日志", 30, false)));
        configService.saveMenuOrder(menus, adminId);
        List<SystemVOs.MenuItem> items = configService.menuOrder().getItems();
        assertEquals("overview", items.get(0).getKey(), "按 sort 排：" + items);
        assertFalse(items.get(2).isVisible(), "藏起来的菜单也在列表里，只是 visible=false");

        SystemDTOs.MenuOrderSave dup = new SystemDTOs.MenuOrderSave();
        dup.setItems(List.of(menuItem("activity", "活动", 1, true), menuItem("activity", "活动（重复）", 2, true)));
        assertMessage("菜单键重复", () -> configService.saveMenuOrder(dup, adminId));

        // ---- 十位编号：前三位是功能段，后七位连号 ----
        String first = serialNumberService.next("777", "用例段");
        String second = serialNumberService.next("777", "用例段");
        assertEquals(10, first.length());
        assertTrue(first.startsWith("777"));
        assertEquals(Long.parseLong(first.substring(3)) + 1, Long.parseLong(second.substring(3)), "连号");
        assertMessage("三位数字", () -> serialNumberService.next("77", "坏段"));
        assertTrue(configService.serials().stream().anyMatch(s -> "777".equals(s.getSegment())));
    }

    @Test
    void vault_folderAccess_publishWindow_andShare() {
        Long root = superAdmin();
        Long outsider = admin("秘书部");
        Long member = admin("宣传部");

        Long folder = vaultService.createFolder(folderSave(null, "协会公共文件"), root);
        assertMessage("只有超级管理员", () -> vaultService.createFolder(folderSave(null, "我自己的"), member));

        // ---- 授权：授给部门，子文件夹继承 ----
        assertMessage("没有这个文件夹的权限", () -> vaultService.files(folder, null, page(10), member));
        SystemDTOs.GrantSave grant = new SystemDTOs.GrantSave();
        grant.setGranteeType(SystemCodes.GRANTEE_DEPARTMENT);
        grant.setDepartment("宣传部");
        grant.setCanWrite(true);
        Long grantId = vaultService.grant(folder, grant, root);
        assertMessage("只有超级管理员能改文件夹权限", () -> vaultService.grant(folder, grant, member));
        Long child = vaultService.createFolder(folderSave(folder, "宣传素材"), member);
        assertTrue(vaultService.canWrite(member, child), "授在上级的权限，下级也算数");
        assertFalse(vaultService.canWrite(outsider, child), "没授过的人还是进不去");

        // 别的部门连文件夹树里都看不到它
        assertTrue(vaultService.tree(outsider).isEmpty(), "看不到的整枝都不出现");
        assertFalse(vaultService.tree(member).isEmpty());

        // ---- 文件：编号自动取、只收本系统上传的 ----
        SystemDTOs.FileSave file = new SystemDTOs.FileSave();
        file.setFolderId(child);
        file.setName("志愿者手册.pdf");
        file.setFileUrl(ownUrl(SystemCodes.DIR_VAULT, "pdf"));
        file.setFileSize(2048L);
        Long fileId = vaultService.addFile(file, member);
        SystemVOs.VaultFile row = vaultService.files(child, null, page(10), member).getRecords().get(0);
        assertEquals(10, row.getSerialNo().length());
        assertTrue(row.getSerialNo().startsWith(SystemCodes.SERIAL_SEGMENT_FILE));
        assertEquals("pdf", row.getFileExt());

        SystemDTOs.FileSave outside = new SystemDTOs.FileSave();
        outside.setFolderId(child);
        outside.setName("外链.pdf");
        outside.setFileUrl("https://evil.example.com/x.pdf");
        assertMessage("请先通过后台上传", () -> vaultService.addFile(outside, member));

        // ---- 删文件夹：有东西就不让删 ----
        assertMessage("文件夹里还有文件", () -> vaultService.deleteFolder(child, member));
        assertMessage("请先删除或移走下级文件夹", () -> vaultService.deleteFolder(folder, root));

        // ---- 公开到小程序 + 开放窗口按时间现算 ----
        assertTrue(vaultService.openFiles().stream().noneMatch(f -> fileId.equals(f.getId())), "没公开就不在");
        SystemDTOs.PublishSave future = new SystemDTOs.PublishSave();
        future.setPublished(true);
        future.setPublishStart(LocalDateTime.now().plusDays(1));
        vaultService.publish(fileId, future, member);
        assertTrue(vaultService.openFiles().stream().noneMatch(f -> fileId.equals(f.getId())), "还没到开放时间");

        SystemDTOs.PublishSave now = new SystemDTOs.PublishSave();
        now.setPublished(true);
        now.setPublishStart(LocalDateTime.now().minusMinutes(1));
        now.setPublishEnd(LocalDateTime.now().plusDays(1));
        now.setAllowDownload(false);
        vaultService.publish(fileId, now, member);
        SystemVOs.OpenFile open = vaultService.openFiles().stream().filter(f -> fileId.equals(f.getId()))
                .findFirst().orElseThrow();
        assertFalse(open.isAllowDownload());
        assertNull(open.getFileUrl(), "关掉下载就不下发地址——只在前端隐藏按钮等于没挡");

        now.setAllowDownload(true);
        vaultService.publish(fileId, now, member);
        assertNotNull(vaultService.openFiles().stream().filter(f -> fileId.equals(f.getId()))
                .findFirst().orElseThrow().getFileUrl());

        SystemDTOs.PublishSave off = new SystemDTOs.PublishSave();
        off.setPublished(false);
        vaultService.publish(fileId, off, member);
        assertTrue(vaultService.openFiles().stream().noneMatch(f -> fileId.equals(f.getId())));
        assertNull(jdbc.queryForObject("SELECT publish_start FROM sys_file WHERE id = ?", LocalDateTime.class, fileId),
                "撤销公开把窗口一起清掉，下次再公开不会套用上次的时间");

        // ---- 分享：随机令牌、要不要登录、撤销之后打不开 ----
        SystemDTOs.ShareSave shareDto = new SystemDTOs.ShareSave();
        shareDto.setRequireLogin(false);
        shareDto.setHours(2);
        SystemVOs.FileShare share = vaultService.share(fileId, shareDto, member);
        assertTrue(share.getToken().length() >= 24, "令牌是随机串不是 id");
        assertEquals("志愿者手册.pdf", vaultService.openShare(share.getToken(), null).getName());
        assertEquals(1, vaultService.shares(fileId, member).get(0).getDownloadCount());

        SystemDTOs.ShareSave needLogin = new SystemDTOs.ShareSave();
        needLogin.setRequireLogin(true);
        SystemVOs.FileShare guarded = vaultService.share(fileId, needLogin, member);
        assertMessage("需要登录", () -> vaultService.openShare(guarded.getToken(), null));
        assertNotNull(vaultService.openShare(guarded.getToken(), outsider), "登录了就能打开（分享是显式给出去的）");

        vaultService.revokeShare(share.getId(), member);
        assertMessage("无效或已过期", () -> vaultService.openShare(share.getToken(), null));
        assertMessage("无效或已过期", () -> vaultService.openShare("随便编一个", null));

        vaultService.revokeGrant(grantId, root);
        assertMessage("没有这个文件夹的权限", () -> vaultService.files(child, null, page(10), member));
    }

    // ================= 造数 =================

    private static SystemDTOs.FolderSave folderSave(Long parentId, String name) {
        SystemDTOs.FolderSave d = new SystemDTOs.FolderSave();
        d.setParentId(parentId);
        d.setName(name);
        return d;
    }

    private static SystemDTOs.MenuOrderSave.Item menuItem(String key, String name, int sort, boolean visible) {
        SystemDTOs.MenuOrderSave.Item item = new SystemDTOs.MenuOrderSave.Item();
        item.setKey(key);
        item.setName(name);
        item.setSort(sort);
        item.setVisible(visible);
        return item;
    }
}
