package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.donate.dto.BookCampaignSaveDTO;
import com.hengde.donate.dto.DonateItemInputDTO;
import com.hengde.donate.dto.RecipientOrgSaveDTO;
import com.hengde.donate.dto.ShipmentCheckDTO;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateMasterDataService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 捐书批用例的公共造数。<b>每条用例自己建活动、单位与志愿者</b>——排行、搜索这类全库查询在共享容器里
 * 会互相污染（MallPickupAndReviewTest 撞过的那一课），各用例只按自己造的 id / 单号断言。
 *
 * @author hengde
 */
final class BookDonationTestSupport {

    static final long ADMIN = 8801L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L + 90_000_000L);

    private BookDonationTestSupport() {
    }

    static long next() {
        return SEQ.incrementAndGet();
    }

    static String phone() {
        return String.format("138%08d", next() % 100_000_000L);
    }

    /** 唯一的快递单号（带小写与空格，顺带验证服务端的规整）。 */
    static String expressNo() {
        return "sf " + next();
    }

    static Long volunteer(VolunteerMapper mapper, CryptoUtil crypto, String name, String phone, boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_book_" + System.nanoTime() + "_" + next());
        v.setRealName(name);
        v.setPhone(crypto.encrypt(phone));
        v.setPhoneHash(crypto.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(registered ? LocalDateTime.now() : null);
        v.setIVolunteerCodeUrl("https://cdn.example.com/ivcode/" + phone + ".png");
        mapper.insert(v);
        return v.getId();
    }

    /** 建一个<b>已发布、正在报名中</b>的捐书活动。 */
    static Long openCampaign(BookCampaignService service) {
        BookCampaignSaveDTO d = campaignDto();
        Long id = service.create(d, ADMIN);
        service.publish(id);
        return id;
    }

    static BookCampaignSaveDTO campaignDto() {
        BookCampaignSaveDTO d = new BookCampaignSaveDTO();
        d.setTitle("用例捐书活动-" + next());
        d.setStartTime(LocalDateTime.now().minusDays(1));
        d.setEndTime(LocalDateTime.now().plusDays(10));
        d.setRecvPhone("0759-8888888");
        d.setRecvAddress("广东省雷州市某路 1 号 恒德协会 转 ");
        return d;
    }

    static Long org(DonateMasterDataService service) {
        RecipientOrgSaveDTO d = new RecipientOrgSaveDTO();
        d.setName("用例小学-" + next());
        d.setOrgType(1);
        return service.createOrg(d, ADMIN);
    }

    static DonateItemInputDTO item(String name, int type, int qty, String barcode) {
        DonateItemInputDTO d = new DonateItemInputDTO();
        d.setName(name);
        d.setItemType(type);
        d.setQuantity(qty);
        d.setCatalogBarcode(barcode);
        return d;
    }

    static ShipmentRegisterDTO shipment(String expressNo, DonateItemInputDTO... items) {
        ShipmentRegisterDTO d = new ShipmentRegisterDTO();
        d.setExpressCode("shunfeng");
        d.setExpressNo(expressNo);
        d.setDonorOrg("用例单位");
        d.setItems(new ArrayList<>(List.of(items)));
        return d;
    }

    static ShipmentCheckDTO.ItemResult result(Long itemId, boolean ok, String remark) {
        ShipmentCheckDTO.ItemResult r = new ShipmentCheckDTO.ItemResult();
        r.setItemId(itemId);
        r.setQualified(ok);
        r.setRemark(remark);
        return r;
    }

    static ShipmentCheckDTO check(ShipmentCheckDTO.ItemResult... results) {
        ShipmentCheckDTO d = new ShipmentCheckDTO();
        d.setResults(new ArrayList<>(List.of(results)));
        return d;
    }
}
