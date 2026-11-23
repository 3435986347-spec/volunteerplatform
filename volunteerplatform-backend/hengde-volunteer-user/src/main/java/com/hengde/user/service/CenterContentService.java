package com.hengde.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.user.dao.UserCenterContentMapper;
import com.hengde.user.dto.CenterContentSaveDTO;
import com.hengde.user.entity.UserCenterContent;
import com.hengde.user.vo.CenterContentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 个人中心内容（Row 42「我的保险：文字和图片展示」、Row 48「联系客服：展示二维码及文字电话 / 后台设置」，V4 个人中心补全批）。
 *
 * <p>协会在后台设置、所有志愿者看同一份（V4规划 Q20 默认；若保险是每人一份保单，要另建按人的表）。
 * 键只认 {@link #KEYS} 里的两个——任意键都收的话，这张表会变成谁都往里塞东西的杂物间。
 * 图片只收本系统传到 {@code center/} 下的（后台 {@code POST /a/files/upload?dir=center}）。</p>
 *
 * @author hengde
 */
@Service
public class CenterContentService {

    public static final String INSURANCE = "insurance";
    public static final String CUSTOMER_SERVICE = "customer-service";
    public static final Set<String> KEYS = Set.of(INSURANCE, CUSTOMER_SERVICE);
    private static final String IMAGE_DIR = "center";

    private UserCenterContentMapper contentMapper;
    private FileStorageService fileStorageService;

    @Autowired
    public void setContentMapper(UserCenterContentMapper contentMapper) {
        this.contentMapper = contentMapper;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 读一份内容；还没设置过返回 null（前端显示「协会还没有设置」，不是错误）。 */
    public CenterContentVO get(String key) {
        requireKey(key);
        UserCenterContent c = contentMapper.selectOne(Wrappers.<UserCenterContent>lambdaQuery()
                .eq(UserCenterContent::getContentKey, key));
        return c == null ? null : toVO(c);
    }

    /** 保存（没有就建、有就整份替换）；并发保存由 {@code uk_content_key} 兜底，撞键改为更新。 */
    public void save(String key, CenterContentSaveDTO dto, Long adminId) {
        requireKey(key);
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        String title = dto == null || !StringUtils.hasText(dto.getTitle()) ? null : dto.getTitle().trim();
        String body = dto == null || !StringUtils.hasText(dto.getBody()) ? null : dto.getBody().trim();
        String images = checkImages(dto == null ? null : dto.getImages());
        if (title == null && body == null && images == null) {
            throw new BusinessException("标题、正文、图片至少填一项");
        }
        LocalDateTime now = LocalDateTime.now();
        if (update(key, title, body, images, adminId, now) == 1) {
            return;
        }
        UserCenterContent c = new UserCenterContent();
        c.setContentKey(key);
        c.setTitle(title);
        c.setBody(body);
        c.setImages(images);
        c.setUpdateBy(adminId);
        try {
            contentMapper.insert(c);
        } catch (DuplicateKeyException e) {
            update(key, title, body, images, adminId, now);
        }
    }

    private int update(String key, String title, String body, String images, Long adminId, LocalDateTime now) {
        return contentMapper.update(null, Wrappers.<UserCenterContent>lambdaUpdate()
                .eq(UserCenterContent::getContentKey, key)
                .set(UserCenterContent::getTitle, title)
                .set(UserCenterContent::getBody, body)
                .set(UserCenterContent::getImages, images)
                .set(UserCenterContent::getUpdateBy, adminId)
                .set(UserCenterContent::getUpdateTime, now));
    }

    private static void requireKey(String key) {
        if (key == null || !KEYS.contains(key)) {
            throw new BusinessException("内容只能是 insurance（我的保险）/ customer-service（联系客服）");
        }
    }

    private String checkImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        if (images.size() > 6) {
            throw new BusinessException("图片最多 6 张");
        }
        if (new HashSet<>(images).size() != images.size()) {
            throw new BusinessException("有重复的图片");
        }
        for (String u : images) {
            if (!StringUtils.hasText(u) || u.length() > 512 || !fileStorageService.isOwnUpload(u, IMAGE_DIR)) {
                throw new BusinessException("图片无效，请重新上传");
            }
        }
        return String.join(",", images);
    }

    private static CenterContentVO toVO(UserCenterContent c) {
        CenterContentVO vo = new CenterContentVO();
        vo.setKey(c.getContentKey());
        vo.setTitle(c.getTitle());
        vo.setBody(c.getBody());
        vo.setImages(StringUtils.hasText(c.getImages()) ? new ArrayList<>(Arrays.asList(c.getImages().split(",")))
                : new ArrayList<>());
        vo.setUpdateTime(c.getUpdateTime());
        return vo;
    }
}
