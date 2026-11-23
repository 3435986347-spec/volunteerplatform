package com.hengde.donate;

import com.hengde.activity.vo.RankingRowView;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dto.ReturnAddressDTO;
import com.hengde.donate.dto.WishDTOs;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateRankingQueryService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.WishVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圆梦微心愿（V3 微心愿批）：完整流程与每一条拒绝条件。
 *
 * <p>每个动作都同时看<b>心愿 / 认领 / 物资 / 提示</b>几处——只看返回值的用例，
 * 在「状态改了、另一张表没跟上」这类缺陷上永远是绿的，而两张表联动是这一批最容易出错的地方。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {WishTestSupport.RECV_NAME, WishTestSupport.RECV_PHONE, WishTestSupport.RECV_ADDRESS})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class WishServiceTest {

    private static final List<String> IMAGES = List.of("https://cdn.example.com/f1.jpg", "https://cdn.example.com/f2.jpg");

    @Autowired
    private WishService wishService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateItemService itemService;
    @Autowired
    private DonateMasterDataService masterDataService;
    @Autowired
    private DonateRankingQueryService rankingQueryService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void claimShipCheckCodeRealize_endToEnd() {
        Long org = BookDonationTestSupport.org(masterDataService);
        String orgName = masterDataService.requireEnabledOrg(org).getName();
        String title = "一个新书包-" + next();
        Long wishId = wishService.create(WishTestSupport.wish(title, "李小明", org), ADMIN);
        Long donor = volunteer("圆梦人甲");
        Long other = volunteer("旁观者乙");

        // 心愿池：打 *，不给接收地址、不给内部备注
        WishVOs.Wish pooled = findIn(wishService.listForVolunteer(other, 0, title, page()).getRecords(), wishId);
        assertNotNull(pooled, "心愿池里应能搜到");
        assertTrue(pooled.isMasked());
        assertEquals("李**", pooled.getChildName());
        assertEquals("雷州***", pooled.getChildSchool());
        assertNull(pooled.getRecvAddress(), "没认领的人看不到物资接收地址");
        assertNull(pooled.getRemark(), "备注是协会内部记录，不对志愿者下发");
        assertFalse(pooled.isClaimedByMe());

        // 认领：认领人拿到全文 + 接收地址
        WishVOs.Wish claimed = wishService.claim(wishId, donor);
        assertFalse(claimed.isMasked());
        assertEquals("李小明", claimed.getChildName());
        assertEquals("雷州市第一小学", claimed.getChildSchool());
        assertEquals(WishTestSupport.ADDRESS, claimed.getRecvAddress());
        assertEquals("恒德协会微心愿组", claimed.getRecvName());
        assertTrue(claimed.isClaimedByMe());
        assertEquals(WishFlow.WISH_CLAIMED, claimed.getStatus());
        assertTrue(wishService.detailForVolunteer(wishId, other).isMasked(), "别人看仍打 *");
        assertNotNull(findIn(wishService.listForVolunteer(other, 1, title, page()).getRecords(), wishId),
                "「已认领」页签里有它");

        // 寄出两件：一件合格、一件不合格
        DonateFlowVOs.Shipment s = wishService.registerShipment(wishId, donor, shipment(expressNo(),
                item("书包", DonateFlow.TYPE_STATIONERY, 1, null),
                item("破文具盒", DonateFlow.TYPE_STATIONERY, 1, null)));
        assertEquals(DonateFlow.BIZ_WISH, s.getBizType());
        assertTrue(s.getCampaignTitle().startsWith("微心愿 HDW") && s.getCampaignTitle().endsWith(title),
                "运单的来源名是这个心愿（编号 + 标题），不是拿认领 id 去活动表对出来的：" + s.getCampaignTitle());
        Map<String, Long> ids = s.getItems().stream()
                .collect(Collectors.toMap(DonateFlowVOs.Item::getName, DonateFlowVOs.Item::getId));
        shipmentService.arrive(s.getId(), ADMIN);
        shipmentService.check(s.getId(), check(result(ids.get("书包"), true, null),
                result(ids.get("破文具盒"), false, "盒盖断裂")), ADMIN);

        // 没有专属码的合格物资不能发放（Row 12 G 顺序：专属条码 → 反馈发放）
        BusinessException noCode = assertThrows(BusinessException.class,
                () -> wishService.realize(wishId, IMAGES, ADMIN));
        assertTrue(noCode.getMessage().contains("专属条码"), noCode.getMessage());
        DonateFlowVOs.ItemLabel label = itemService.generateCode(ids.get("书包"), ADMIN);
        assertEquals("李小明", label.getChildName(), "面单上有受捐学生（Row 12 G）");
        assertTrue(label.getWishNo().startsWith("HDW"), "面单上有心愿编号");
        assertEquals(orgName, label.getRecipientOrgName(), "面单上有受捐单位（上报单位）");
        assertEquals("圆梦人甲", label.getDonorName());

        wishService.realize(wishId, IMAGES, ADMIN);

        // 物资：合格的送达、受捐单位 = 上报单位；不合格的不动（它走退回流程）
        assertEquals(DonateFlow.ITEM_DELIVERED, itemStatus(ids.get("书包")));
        assertEquals(orgName, jdbc.queryForObject("SELECT recipient_org_name FROM donate_item WHERE id = ?",
                String.class, ids.get("书包")));
        assertEquals(DonateFlow.ITEM_REJECTED, itemStatus(ids.get("破文具盒")));
        // 心愿与认领同时置已实现
        WishVOs.Wish realized = wishService.detailForVolunteer(wishId, donor);
        assertEquals(WishFlow.WISH_REALIZED, realized.getStatus());
        assertEquals(IMAGES, realized.getFeedbackImages());
        WishVOs.Wish seenByOther = wishService.detailForVolunteer(wishId, other);
        assertTrue(seenByOther.isMasked());
        assertTrue(seenByOther.getFeedbackImages().isEmpty(), "发放照片里是孩子本人，只给认领人");
        // 微心愿中心：认领 + 包裹 + 轨迹（最后一条是发放）
        WishVOs.Claim mine = wishService.myClaims(donor, page()).getRecords().get(0);
        assertEquals(WishFlow.CLAIM_REALIZED, mine.getStatus());
        assertNotNull(mine.getRealizeTime());
        assertEquals(1, mine.getShipments().size());
        assertTrue(mine.getShipments().get(0).getTraces().stream()
                .anyMatch(t -> t.getAction() == DonateFlow.ACT_DELIVER), "发放要留轨迹——轨迹对捐赠人公开");
        // 站内提示
        assertTrue(notificationTypes(donor).contains(VolunteerNotification.TYPE_WISH_REALIZED));
        // 排行数据源：按认领上的实现时间
        List<RankingRowView> rows = rankingQueryService.topByRealizedWishes(
                LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(10), 1000);
        assertTrue(rows.stream().anyMatch(r -> r.getVolunteerId().equals(donor)
                && ((Number) r.getMetricValue()).longValue() == 1L), "圆梦人甲本月圆了 1 个心愿");
        // 已实现：不能再撤销 / 取消 / 寄送 / 实现
        assertThrows(BusinessException.class, () -> wishService.revokeClaim(wishId, "误操作", ADMIN));
        assertThrows(BusinessException.class, () -> wishService.cancelClaim(wishId, donor));
        assertThrows(BusinessException.class, () -> wishService.registerShipment(wishId, donor,
                shipment(expressNo(), item("再寄一本书", DonateFlow.TYPE_BOOK, 1, null))));
        assertThrows(BusinessException.class, () -> wishService.realize(wishId, IMAGES, ADMIN));
        // 捐书记录（Row 38）里：这件物资的来源是心愿，而不是某个捐书活动
        DonateFlowVOs.Item bag = itemService.listMine(donor, page()).getRecords().stream()
                .filter(i -> i.getName().equals("书包")).findFirst().orElseThrow();
        assertTrue(bag.getCampaignTitle().startsWith("微心愿 "), bag.getCampaignTitle());
    }

    @Test
    void viewingNeedsVerifiedPhone_claimingNeedsRegistration() {
        Long wishId = wishService.create(WishTestSupport.wish("看心愿-" + next(), "周小红", null), ADMIN);
        Long noPhone = WishTestSupport.volunteerWithoutPhone(volunteerMapper);
        BusinessException list = assertThrows(BusinessException.class,
                () -> wishService.listForVolunteer(noPhone, 0, null, page()));
        assertTrue(list.getMessage().contains("验证手机号"), list.getMessage());
        assertThrows(BusinessException.class, () -> wishService.detailForVolunteer(wishId, noPhone));

        // 手机号登录的游客：能看（打 *），不能认领
        Long guest = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "游客丙", phone(), false);
        assertTrue(wishService.detailForVolunteer(wishId, guest).isMasked());
        BusinessException claim = assertThrows(BusinessException.class, () -> wishService.claim(wishId, guest));
        assertTrue(claim.getMessage().contains("实名"), claim.getMessage());
        assertEquals(WishFlow.WISH_OPEN, wishStatus(wishId), "失败的认领不留痕迹");
        assertEquals(0, claimCount(wishId));
    }

    @Test
    void claimIsExclusive_andClaimedWishesAreFrozen() {
        Long wishId = wishService.create(WishTestSupport.wish("独占-" + next(), "吴小军", null), ADMIN);
        Long a = volunteer("认领人甲");
        Long b = volunteer("晚一步乙");
        wishService.claim(wishId, a);
        assertTrue(assertThrows(BusinessException.class, () -> wishService.claim(wishId, a))
                .getMessage().contains("你已经认领"));
        assertTrue(assertThrows(BusinessException.class, () -> wishService.claim(wishId, b))
                .getMessage().contains("别人认领"));
        assertTrue(assertThrows(BusinessException.class, () -> wishService.takeDown(wishId))
                .getMessage().contains("只有待认领"), "已认领的不能下架，否则认领人手里的心愿凭空消失");
        assertTrue(assertThrows(BusinessException.class,
                () -> wishService.update(wishId, WishTestSupport.wish("改个标题", "吴小军", null)))
                .getMessage().contains("已被认领"), "认领之后改资料等于换了一个心愿给他");

        // 下架的心愿对志愿者等于不存在
        Long down = wishService.create(WishTestSupport.wish("下架-" + next(), "郑小丽", null), ADMIN);
        wishService.takeDown(down);
        assertEquals("心愿不存在", assertThrows(BusinessException.class,
                () -> wishService.detailForVolunteer(down, b)).getMessage());
        assertEquals("心愿不存在", assertThrows(BusinessException.class, () -> wishService.claim(down, b)).getMessage());
        assertNull(findIn(wishService.listForVolunteer(b, 0, null, page()).getRecords(), down));
        // 下架中可以改资料（还没人认领过），重新上架后可以认领
        wishService.update(down, WishTestSupport.wish("下架后改好了", "郑小丽", null));
        wishService.restore(down);
        assertEquals("下架后改好了", wishService.claim(down, b).getTitle());
        assertThrows(BusinessException.class, () -> wishService.listForVolunteer(b, 7, null, page()));
    }

    @Test
    void cancelAndRevoke_releaseTheWishOnlyWhenNoGoodsAreInPlay() {
        Long wishId = wishService.create(WishTestSupport.wish("取消撤销-" + next(), "冯小宝", null), ADMIN);
        Long a = volunteer("先认领甲");
        Long b = volunteer("后认领乙");
        wishService.claim(wishId, a);
        DonateFlowVOs.Shipment sa = wishService.registerShipment(wishId, a, shipment(expressNo(),
                item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));

        // 包裹在路上：认领人不能取消认领、后台不能撤销；别人更不能替他取消
        assertTrue(assertThrows(BusinessException.class, () -> wishService.cancelClaim(wishId, a))
                .getMessage().contains("我的运单"));
        assertTrue(assertThrows(BusinessException.class, () -> wishService.revokeClaim(wishId, "资料有误", ADMIN))
                .getMessage().contains("流转中"));
        assertEquals("你没有认领这个心愿", assertThrows(BusinessException.class,
                () -> wishService.cancelClaim(wishId, b)).getMessage());

        // 他先取消包裹，再取消认领：心愿回池、占位释放，别人可以认领
        shipmentService.cancel(sa.getId(), a);
        wishService.cancelClaim(wishId, a);
        assertEquals(WishFlow.WISH_OPEN, wishStatus(wishId));
        wishService.claim(wishId, b);
        WishVOs.Claim aClaim = wishService.myClaims(a, page()).getRecords().get(0);
        assertEquals(WishFlow.CLAIM_CANCELLED, aClaim.getStatus());
        assertTrue(aClaim.getWish().isMasked(), "不再是认领人，就不再有看全文的理由");
        assertNull(aClaim.getWish().getRecvAddress());

        // 乙寄来的全部不合格：物资对心愿已经没用，后台可以撤销（原因必填）
        DonateFlowVOs.Shipment sb = wishService.registerShipment(wishId, b, shipment(expressNo(),
                item("旧书包", DonateFlow.TYPE_STATIONERY, 1, null)));
        shipmentService.arrive(sb.getId(), ADMIN);
        shipmentService.check(sb.getId(), check(result(sb.getItems().get(0).getId(), false, "破损")), ADMIN);
        assertTrue(assertThrows(BusinessException.class, () -> wishService.revokeClaim(wishId, "  ", ADMIN))
                .getMessage().contains("撤销原因"));
        wishService.revokeClaim(wishId, "寄来的物资均不合格，心愿重新开放", ADMIN);
        assertEquals(WishFlow.WISH_OPEN, wishStatus(wishId));
        WishVOs.Claim bClaim = wishService.myClaims(b, page()).getRecords().get(0);
        assertEquals(WishFlow.CLAIM_REVOKED, bClaim.getStatus());
        assertEquals("寄来的物资均不合格，心愿重新开放", bClaim.getCancelReason());
        String content = jdbc.queryForObject("SELECT content FROM volunteer_notification WHERE volunteer_id = ? "
                + "AND type = ? ORDER BY id DESC LIMIT 1", String.class, b, VolunteerNotification.TYPE_WISH_CLAIM_REVOKED);
        assertTrue(content.contains("均不合格"), "撤销要告诉认领人原因：" + content);

        // 认领已撤销：后台不能再往乙那个包裹里补录物资（补进来的东西没有下一步）
        assertTrue(assertThrows(BusinessException.class, () -> shipmentService.addItem(sb.getId(),
                item("补录的书", DonateFlow.TYPE_BOOK, 1, null), ADMIN)).getMessage().contains("认领已经结束"));
        // 不合格物资的退回与认领无关，照走
        ReturnAddressDTO addr = new ReturnAddressDTO();
        addr.setName("后认领乙");
        addr.setPhone("13900001111");
        addr.setAddress("湛江市某小区");
        shipmentService.submitReturnAddress(sb.getId(), b, addr);
    }

    @Test
    void realize_requiresSettledParcelsAndQualifiedGoods() {
        Long wishId = wishService.create(WishTestSupport.wish("实现前置-" + next(), "褚小燕", null), ADMIN);
        Long donor = volunteer("前置甲");
        assertTrue(assertThrows(BusinessException.class, () -> wishService.realize(wishId, IMAGES, ADMIN))
                .getMessage().contains("还没有人认领"));
        wishService.claim(wishId, donor);
        assertTrue(assertThrows(BusinessException.class, () -> wishService.realize(wishId, IMAGES, ADMIN))
                .getMessage().contains("还没有核对合格"));

        DonateFlowVOs.Shipment first = wishService.registerShipment(wishId, donor, shipment(expressNo(),
                item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
        DonateFlowVOs.Shipment second = wishService.registerShipment(wishId, donor, shipment(expressNo(),
                item("文具", DonateFlow.TYPE_STATIONERY, 1, null)));
        shipmentService.arrive(first.getId(), ADMIN);
        Long bag = first.getItems().get(0).getId();
        shipmentService.check(first.getId(), check(result(bag, true, null)), ADMIN);
        itemService.generateCode(bag, ADMIN);
        assertTrue(assertThrows(BusinessException.class, () -> wishService.realize(wishId, IMAGES, ADMIN))
                .getMessage().contains("未到货或未核对"), "第二个包裹还在路上：实现了它就没有下一步");

        assertTrue(assertThrows(BusinessException.class, () -> wishService.realize(wishId, List.of(), ADMIN))
                .getMessage().contains("1~9"));
        assertTrue(assertThrows(BusinessException.class,
                () -> wishService.realize(wishId, Collections.nCopies(10, "https://cdn.example.com/x.jpg"), ADMIN))
                .getMessage().contains("最多"));

        shipmentService.cancel(second.getId(), donor);
        wishService.realize(wishId, IMAGES, ADMIN);
        assertEquals(WishFlow.WISH_REALIZED, wishStatus(wishId));
        assertEquals(DonateFlow.ITEM_CANCELLED, itemStatus(second.getItems().get(0).getId()));
    }

    @Test
    void import_isAllOrNothing_andStoresChildDataEncrypted() {
        Long org = BookDonationTestSupport.org(masterDataService);
        String orgName = masterDataService.requireEnabledOrg(org).getName();
        long n = next();
        String existingNo = "LZ-EXIST-" + n;
        WishDTOs.Save existing = WishTestSupport.wish("已有编号-" + n, "蒋小强", null);
        existing.setWishNo(existingNo);
        wishService.create(existing, ADMIN);

        List<WishDTOs.ImportRow> bad = new ArrayList<>();
        bad.add(row(null, "书包-" + n, "张三", "男", 10, orgName));          // 第 2 行：合格
        bad.add(new WishDTOs.ImportRow());                                   // 第 3 行：空行，跳过
        bad.add(row(null, null, "李四", null, null, null));                  // 第 4 行：缺标题
        bad.add(row(null, "文具-" + n, "王五", "未知", null, null));          // 第 5 行：性别
        bad.add(row(null, "球鞋-" + n, "赵六", null, null, "不存在的单位"));    // 第 6 行：单位对不上
        bad.add(row("DUP-" + n, "水杯-" + n, "钱七", "女", 8, null));        // 第 7 行：合格
        bad.add(row("DUP-" + n, "雨伞-" + n, "孙八", null, null, null));      // 第 8 行：文件内重号
        bad.add(row(existingNo, "台灯-" + n, "周九", null, null, null));       // 第 9 行：库里已有
        WishVOs.ImportResult r = wishService.importRows(bad, ADMIN);
        assertEquals(7, r.getTotal(), "空行不计");
        assertEquals(0, r.getImported(), "全成或全不成");
        assertEquals(5, r.getErrors().size(), r.getErrors().toString());
        assertError(r, 4, "缺少标题");
        assertError(r, 5, "性别");
        assertError(r, 6, "不存在的单位");
        assertError(r, 8, "在文件里重复");
        assertError(r, 9, "已存在");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM donate_wish WHERE title = ?", Integer.class,
                "书包-" + n), "有错就一行都不进");

        List<WishDTOs.ImportRow> good = new ArrayList<>();
        WishDTOs.ImportRow first = row(null, "书包-" + n, "张三", "男", 10, orgName);
        first.setChildSchool("雷州市实验小学");
        good.add(first);
        good.add(row("DUP-" + n, "水杯-" + n, "钱七", "2", 8, null));
        WishVOs.ImportResult ok = wishService.importRows(good, ADMIN);
        assertEquals(2, ok.getImported());
        assertTrue(ok.getErrors().isEmpty());
        Map<String, Object> raw = jdbc.queryForMap("SELECT wish_no, child_name, child_school, child_gender, "
                + "report_org_id, report_org_name, status FROM donate_wish WHERE title = ?", "书包-" + n);
        assertTrue(raw.get("wish_no").toString().startsWith("HDW"), "没给编号的由系统生成");
        assertNotEquals("张三", raw.get("child_name"), "未成年人姓名密文存储");
        assertEquals("张三", cryptoUtil.decrypt(raw.get("child_name").toString()));
        assertEquals("雷州市实验小学", cryptoUtil.decrypt(raw.get("child_school").toString()));
        assertEquals(1, ((Number) raw.get("child_gender")).intValue());
        assertEquals(org, ((Number) raw.get("report_org_id")).longValue(), "上报单位按名称对上主数据");
        assertEquals(orgName, raw.get("report_org_name"));
        assertEquals(WishFlow.WISH_OPEN, ((Number) raw.get("status")).intValue());

        assertThrows(BusinessException.class, () -> wishService.importRows(List.of(), ADMIN));
        List<WishDTOs.ImportRow> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(row(null, "超量" + i, "某某", null, null, null));
        }
        assertTrue(assertThrows(BusinessException.class, () -> wishService.importRows(tooMany, ADMIN))
                .getMessage().contains("一次最多"));
    }

    @Test
    void adminSeesEverything_listDetailExport() {
        Long org = BookDonationTestSupport.org(masterDataService);
        String tag = "后台看-" + next();
        Long wishId = wishService.create(WishTestSupport.wish(tag, "卫小梅", org), ADMIN);
        String donorPhone = phone();
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "后台可见甲", donorPhone, true);
        wishService.claim(wishId, donor);
        wishService.registerShipment(wishId, donor, shipment(expressNo(), item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));

        WishVOs.AdminDetail d = wishService.detailForAdmin(wishId);
        assertEquals("卫小梅", d.getWish().getChildName());
        assertEquals("父亲在外务工，由奶奶照看", d.getWish().getRemark());
        assertEquals("后台可见甲", d.getWish().getClaimerName());
        assertEquals(1, d.getClaims().size());
        assertEquals(donorPhone, d.getClaims().get(0).getClaimerPhone(), "协会要联系认领人");
        assertEquals(1, d.getClaims().get(0).getShipments().size());

        WishVOs.Wish listed = findIn(wishService.listForAdmin(page(), WishFlow.WISH_CLAIMED, tag).getRecords(), wishId);
        assertNotNull(listed);
        assertEquals("后台可见甲", listed.getClaimerName());
        assertFalse(listed.isMasked());

        List<WishVOs.ExportRow> rows = wishService.exportRows(null, tag);
        assertEquals(1, rows.size());
        assertEquals("卫小梅", rows.get(0).getChildName());
        assertEquals("雷州市第一小学", rows.get(0).getChildSchool());
        assertEquals("男", rows.get(0).getChildGender());
        assertEquals("后台可见甲", rows.get(0).getClaimerName());
        assertEquals("已认领", rows.get(0).getStatus());
    }

    @Test
    void schoolLength_fitsTheEncryptedColumn() {
        WishDTOs.Save d = WishTestSupport.wish("学校名长度-" + next(), "韩小冬", null);
        d.setChildSchool("雷".repeat(64));
        Long id = wishService.create(d, ADMIN);
        assertEquals("雷".repeat(64), wishService.detailForAdmin(id).getWish().getChildSchool(),
                "64 个汉字加密后仍放得进 VARCHAR(512)");
        d.setChildSchool("雷".repeat(65));
        assertTrue(assertThrows(BusinessException.class, () -> wishService.create(d, ADMIN))
                .getMessage().contains("学校"));
    }

    // ---------- helpers ----------

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private static PageQuery page() {
        return new PageQuery();
    }

    private static WishVOs.Wish findIn(List<WishVOs.Wish> list, Long id) {
        return list.stream().filter(w -> w.getId().equals(id)).findFirst().orElse(null);
    }

    private static WishDTOs.ImportRow row(String no, String title, String name, String gender, Integer age, String org) {
        WishDTOs.ImportRow r = new WishDTOs.ImportRow();
        r.setWishNo(no);
        r.setTitle(title);
        r.setChildName(name);
        r.setChildGender(gender);
        r.setChildAge(age);
        r.setReportOrgName(org);
        return r;
    }

    private static void assertError(WishVOs.ImportResult r, int rowNo, String fragment) {
        assertTrue(r.getErrors().stream().anyMatch(e -> e.startsWith("第 " + rowNo + " 行") && e.contains(fragment)),
                "第 " + rowNo + " 行应报「" + fragment + "」：" + r.getErrors());
    }

    private int wishStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM donate_wish WHERE id = ?", Integer.class, id);
    }

    private int itemStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM donate_item WHERE id = ?", Integer.class, id);
    }

    private int claimCount(Long wishId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM donate_wish_claim WHERE wish_id = ?", Integer.class, wishId);
    }

    private List<Integer> notificationTypes(Long volunteerId) {
        return jdbc.queryForList("SELECT type FROM volunteer_notification WHERE volunteer_id = ?", Integer.class,
                volunteerId);
    }
}
