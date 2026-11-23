package com.hengde.publicity;

import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.publicity.dao.BannerMapper;
import com.hengde.publicity.dto.BannerDTO;
import com.hengde.publicity.service.PublicityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfig.class)
class PublicityTest {

    private BannerMapper bannerMapper;
    private PublicityService publicityService;

    @Autowired
    public void setBannerMapper(BannerMapper bannerMapper) {
        this.bannerMapper = bannerMapper;
    }

    @Autowired
    public void setPublicityService(PublicityService publicityService) {
        this.publicityService = publicityService;
    }

    @Test
    void canCreateBannerAfterMigration() {
        BannerDTO dto = new BannerDTO();
        dto.setTitle("首页轮播");
        dto.setImageUrl("https://example.com/banner.png");
        dto.setStatus(1);

        Long id = publicityService.createBanner(dto);

        assertNotNull(id);
        assertNotNull(bannerMapper.selectById(id));
    }
}
