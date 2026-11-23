package com.hengde.enterprise;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.dto.MallGoodsSpecDTO;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 赞助商品 / 企业积分用例造数：后台直接建正常企业（不走短信）、已实名带手机号的志愿者、预置积分。
 *
 * @author hengde
 */
final class SponsorTestSupport {

    private SponsorTestSupport() {
    }

    static Long normalEnterprise(EnterpriseAdminService adminService, String name) {
        EnterpriseDTOs.AdminCreate c = new EnterpriseDTOs.AdminCreate();
        c.setName(name + EnterpriseTestSupport.next());
        c.setCreditCode(EnterpriseTestSupport.creditCode());
        c.setLeaderName("负责人");
        c.setLeaderPhone(EnterpriseTestSupport.phone());
        c.setUsername(EnterpriseTestSupport.username("sp_"));
        c.setPassword("pass1234");
        return adminService.create(c, EnterpriseTestSupport.ADMIN);
    }

    static MallGoodsSaveDTO goods(String name, int points, int stock) {
        MallGoodsSaveDTO d = new MallGoodsSaveDTO();
        d.setName(name + EnterpriseTestSupport.next());
        d.setDetail("赞助商品");
        MallGoodsSpecDTO s = new MallGoodsSpecDTO();
        s.setName("标准");
        s.setPoints(points);
        s.setStock(stock);
        d.setSpecs(List.of(s));
        return d;
    }

    static Long volunteer(VolunteerMapper mapper, CryptoUtil crypto, String phone) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:sponsor:" + System.nanoTime() + ":" + EnterpriseTestSupport.next());
        v.setRealName("王小明");
        v.setPhone(crypto.encrypt(phone));
        v.setPhoneHash(crypto.hashPhone(phone));
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setRegisterTime(LocalDateTime.now());
        mapper.insert(v);
        return v.getId();
    }

    static void givePoints(JdbcTemplate jdbc, Long volunteerId, int points) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, operator_type, create_time, "
                + "update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)", volunteerId, points, PointSourceType.MANUAL);
    }

    static Long specOf(JdbcTemplate jdbc, Long goodsId) {
        return jdbc.queryForObject("SELECT id FROM mall_goods_spec WHERE goods_id = ? AND is_deleted = 0 ORDER BY id LIMIT 1", Long.class, goodsId);
    }
}
