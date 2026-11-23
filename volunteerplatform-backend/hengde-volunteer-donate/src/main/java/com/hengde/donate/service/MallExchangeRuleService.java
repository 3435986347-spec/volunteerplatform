package com.hengde.donate.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.donate.dao.MallExchangeRuleMapper;
import com.hengde.donate.dto.ExchangeRuleSaveDTO;
import com.hengde.donate.entity.MallExchangeRule;
import com.hengde.donate.vo.ExchangeRuleVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 积分兑换规则（Row 8 C「兑换规则（文字 + 图片）」）——<b>单行表，固定 id=1</b>。
 *
 * <p><b>为什么是表不是配置项</b>：自提点走配置，因为它是运营常量、且要下单快照；
 * 兑换规则是<b>内容</b>，协会会改、会随活动调整——配置项意味着改一句文案要重启服务。
 * 两者形似而性质相反。</p>
 *
 * <p><b>为什么不复用公告</b>：公告流会把它列进去，要排除就得给公告加标记列（同样要迁移），
 * 还把商城概念漏进 publicity，而且有人删掉那条公告会让商城页面静默变空。</p>
 *
 * <p><b>换行分隔而非逗号</b>：URL 里可以合法地出现逗号，不可能出现换行。
 * 项目已有的 {@code service_guarantees} 用逗号，是因为那存的是固定 key、不是 URL。</p>
 *
 * @author hengde
 */
@Service
public class MallExchangeRuleService {

    /** 单行表的固定主键。 */
    private static final long SINGLETON_ID = 1L;

    /** 库里的分隔符。URL 不可能包含它，故不必转义、也不会切错。 */
    private static final String SEPARATOR = "\n";

    private MallExchangeRuleMapper ruleMapper;

    @Autowired
    public void setRuleMapper(MallExchangeRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /**
     * 取当前规则。志愿者端与管理端<b>同一份</b>——规则本就是给志愿者看的，没有内部字段。
     *
     * <p>行不存在（被人手工删了）时返回<b>空内容而不是抛异常</b>：
     * 志愿者点开看到一片空白是可接受的，看到错误提示则会以为程序坏了。
     * 下一次保存会由 {@code save} 的 INSERT 分支把这行重建出来。</p>
     */
    public ExchangeRuleVO get() {
        MallExchangeRule row = ruleMapper.selectById(SINGLETON_ID);
        ExchangeRuleVO vo = new ExchangeRuleVO();
        if (row == null) {
            vo.setImages(List.of());
            return vo;
        }
        vo.setContent(row.getContent());
        vo.setImages(splitImages(row.getImages()));
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    /**
     * 保存规则（覆盖式，无版本）。
     *
     * <p>一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 完成「有则改、无则建」——
     * 「先查再决定 insert 还是 update」在并发下会一个插入、一个撞主键，是本项目反复清理过的形状。</p>
     *
     * @param dto     正文与配图
     * @param adminId 操作人，落审计
     */
    public void save(ExchangeRuleSaveDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        String images = joinImages(dto.getImages());
        if (ruleMapper.save(dto.getContent(), images, adminId) < 1) {
            throw new BusinessException("兑换规则保存失败");
        }
    }

    private List<String> splitImages(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(SEPARATOR)).map(String::trim).filter(StringUtils::hasText).toList();
    }

    /**
     * 拼成库里的换行分隔串。
     *
     * <p><b>逐个校验 URL 里不含换行</b>——虽然合法 URL 不会有，但入参是客户端送的：
     * 塞一个进来就能凭空多出一张图，或把一条 URL 切成两条指向别处的碎片。
     * 分隔符方案的代价就是这一道校验，不能省。</p>
     */
    private String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        List<String> cleaned = images.stream().filter(StringUtils::hasText).map(String::trim).toList();
        for (String url : cleaned) {
            if (url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0) {
                throw new BusinessException("图片地址不合法");
            }
        }
        return cleaned.isEmpty() ? null : String.join(SEPARATOR, cleaned);
    }
}
