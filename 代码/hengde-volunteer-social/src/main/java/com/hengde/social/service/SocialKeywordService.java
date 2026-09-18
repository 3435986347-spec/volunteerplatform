package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.social.dao.SocialKeywordMapper;
import com.hengde.social.entity.SocialKeyword;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 风控关键词（Row 23 F「后台可设置关键词，用户发布的帖子……如触发关键词，则自动进入后台插队审核」）。
 *
 * <p>命中＝正文不区分大小写地<b>包含</b>这个词。每次发帖 / 改帖现查全表（关键词表不会大，省掉缓存失效的麻烦——
 * 协会刚加的词下一条帖子就要生效）。</p>
 *
 * @author hengde
 */
@Service
public class SocialKeywordService {

    private SocialKeywordMapper keywordMapper;

    @Autowired
    public void setKeywordMapper(SocialKeywordMapper keywordMapper) {
        this.keywordMapper = keywordMapper;
    }

    /** 命中的关键词（按添加先后，去重）；没有命中返回空。 */
    public List<String> hits(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return keywordMapper.selectList(Wrappers.<SocialKeyword>lambdaQuery().orderByAsc(SocialKeyword::getId)).stream()
                .map(SocialKeyword::getWord)
                .filter(w -> lower.contains(w.toLowerCase(Locale.ROOT)))
                .distinct()
                .toList();
    }

    public List<SocialKeyword> list() {
        return keywordMapper.selectList(Wrappers.<SocialKeyword>lambdaQuery().orderByDesc(SocialKeyword::getId));
    }

    public Long add(Long adminId, String word) {
        String w = word == null ? "" : word.trim();
        if (w.isEmpty() || w.length() > 50) {
            throw new BusinessException("关键词 1~50 字");
        }
        SocialKeyword k = new SocialKeyword();
        k.setWord(w);
        k.setCreatedBy(adminId);
        k.setCreateTime(LocalDateTime.now());
        try {
            keywordMapper.insert(k);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已经有这个关键词了");
        }
        return k.getId();
    }

    public void delete(Long id) {
        if (keywordMapper.deleteById(id) == 0) {
            throw new BusinessException("关键词不存在");
        }
    }
}
