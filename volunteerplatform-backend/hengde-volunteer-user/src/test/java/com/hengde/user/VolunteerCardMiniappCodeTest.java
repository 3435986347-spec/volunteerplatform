package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.MiniappCodeService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.user.service.VolunteerCardService;
import com.hengde.user.vo.VolunteerCardVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者证的小程序码那一支（没有真实 AppID，用替身代替微信接口）：配置了就出小程序码、scene 是令牌、<b>按令牌缓存不重复调微信</b>、
 * 重置后换新令牌重新生成；微信那边失败时回退普通二维码。
 *
 * <p>⚠️ 替身证明的是「本系统怎么用小程序码」，<b>证明不了微信真的接受这个 scene 与页面</b>——那一段要有 AppID 之后实测。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {"hengde.user.card.miniapp-page=pages/card/verify", "hengde.user.card.verify-url=https://example.org/card"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, VolunteerCardMiniappCodeTest.FakeCode.class})
class VolunteerCardMiniappCodeTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static volatile boolean failing;

    @TestConfiguration
    static class FakeCode {
        @Bean
        @Primary
        MiniappCodeService fakeMiniappCodeService() {
            return new MiniappCodeService() {
                @Override
                public boolean configured() {
                    return true;
                }

                @Override
                public byte[] unlimited(String scene, String page, String envVersion) {
                    CALLS.add(scene + "@" + page + "@" + envVersion);
                    return failing ? null : ("PNG:" + scene).getBytes();
                }
            };
        }
    }

    @Autowired
    private VolunteerCardService cardService;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void miniappCode_scenesAreTokens_cachedPerToken_fallbackWhenWechatFails() {
        failing = false;
        Volunteer v = new Volunteer();
        v.setOpenid("test:card-code:" + System.nanoTime());
        v.setRealName("码测试");
        v.setStatus(0);
        v.setManagerFlag(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);

        VolunteerCardVO.Mine first = cardService.myCard(v.getId());
        assertEquals("MINIAPP_CODE", first.getQrType());
        String token = first.getQrContent();
        assertTrue(token.matches("[A-Za-z0-9_-]{32}"), token);
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(("PNG:" + token).getBytes()), first.getQrImage());
        assertEquals(List.of(token + "@pages/card/verify@release"), new ArrayList<>(callsFor(token)));

        cardService.myCard(v.getId());
        cardService.myCard(v.getId());
        assertEquals(1, callsFor(token).size(), "同一令牌只调一次微信（Redis 缓存）");
        assertEquals(token, cardService.verify(token).getNo().isEmpty() ? null : token);

        failing = true;
        VolunteerCardVO.Mine renewed = cardService.reset(v.getId());
        assertEquals("QR_CODE", renewed.getQrType(), "新令牌生成小程序码失败：回退普通二维码");
        assertTrue(renewed.getQrContent().startsWith("https://example.org/card?token="), renewed.getQrContent());
        String fresh = renewed.getQrContent().substring("https://example.org/card?token=".length());
        assertEquals(1, callsFor(fresh).size());
        failing = false;
    }

    private static List<String> callsFor(String token) {
        return CALLS.stream().filter(c -> c.startsWith(token + "@")).toList();
    }
}
