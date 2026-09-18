package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.donate.dto.WishDTOs;

import java.time.LocalDateTime;

/**
 * 微心愿用例的共用造数。三个用例类用<b>同一组</b>配置属性（常量），好让 Spring 复用同一个测试上下文——
 * 属性不同就是另一个上下文，要多起一遍。
 *
 * @author hengde
 */
final class WishTestSupport {

    static final String RECV_NAME = "hengde.donate.wish.recv-name=恒德协会微心愿组";
    static final String RECV_PHONE = "hengde.donate.wish.recv-phone=0759-6666666";
    static final String RECV_ADDRESS = "hengde.donate.wish.recv-address=雷州市某路 1 号 恒德协会办公室";
    static final String ADDRESS = "雷州市某路 1 号 恒德协会办公室";

    private WishTestSupport() {
    }

    static WishDTOs.Save wish(String title, String childName, Long orgId) {
        WishDTOs.Save d = new WishDTOs.Save();
        d.setTitle(title);
        d.setContent("想要一个新书包");
        d.setStory("家离学校很远，旧书包背带断了");
        d.setImageUrl("https://cdn.example.com/wish/" + BookDonationTestSupport.next() + ".jpg");
        d.setChildName(childName);
        d.setChildGender(1);
        d.setChildAge(9);
        d.setChildSchool("雷州市第一小学");
        d.setChildGrade("三年级");
        d.setReportOrgId(orgId);
        d.setRemark("父亲在外务工，由奶奶照看");
        return d;
    }

    /** 已实名、但没有手机号的账号（验证「查看心愿须已验手机号」）。 */
    static Long volunteerWithoutPhone(VolunteerMapper mapper) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_wish_nophone_" + System.nanoTime() + "_" + BookDonationTestSupport.next());
        v.setRealName("无手机号");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        mapper.insert(v);
        return v.getId();
    }
}
