package com.hengde.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.social.constant.SocialGovCodes;
import com.hengde.social.dao.SocialBanMapper;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.entity.SocialBan;
import com.hengde.social.vo.SocialGovVOs;
import com.hengde.social.vo.SocialVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 社区禁言（Row 23 F「后台可以给予某个用户限制，如：禁止发帖几天、禁止评论几天、禁止点赞几天」，Q2）。
 *
 * <p><b>即时生效、不走奖惩两级审核</b>（Q2 默认：社区审核员的日常动作，走奖惩太重）；<b>限制本身只写进 auth 的
 * {@code volunteer_sanction}</b>（来源类型 {@link VolunteerSanction#SOURCE_SOCIAL_BAN}，source_id＝禁言记录 id），
 * 发帖 / 评论 / 点赞的闸门只认那一张表——不另建「能不能发帖」的第二套事实来源（承重条款 1）。本人在奖惩中心的「处置查看」里看得到。</p>
 *
 * <p>只能开社区的能力域（全部社区写入 / 禁止发帖 / 评论 / 点赞），必须给天数——不设期限的限制是奖惩的事。</p>
 *
 * @author hengde
 */
@Service
public class SocialBanService {

    private SocialBanMapper banMapper;
    private SanctionService sanctionService;
    private SocialCardService cardService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setBanMapper(SocialBanMapper banMapper) {
        this.banMapper = banMapper;
    }

    @Autowired
    public void setSanctionService(SanctionService sanctionService) {
        this.sanctionService = sanctionService;
    }

    @Autowired
    public void setCardService(SocialCardService cardService) {
        this.cardService = cardService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Long ban(Long adminId, SocialGovDTOs.BanSave dto) {
        if (dto == null || dto.getVolunteerId() == null) {
            throw new BusinessException("请选择志愿者");
        }
        if (dto.getScope() == null || !SocialGovCodes.BAN_SCOPES.contains(dto.getScope())) {
            throw new BusinessException("禁言只能限制社区（全部 / 发帖 / 评论 / 点赞）");
        }
        if (dto.getDays() == null || dto.getDays() < 1 || dto.getDays() > VolunteerSanction.MAX_SANCTION_DAYS) {
            throw new BusinessException("禁言天数须在 1~" + VolunteerSanction.MAX_SANCTION_DAYS + " 之间");
        }
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty() || reason.length() > 255) {
            throw new BusinessException("请填写原因（不超过 255 字）");
        }
        cardService.requireCard(dto.getVolunteerId());
        return transactionTemplate.execute(s -> {
            SocialBan b = new SocialBan();
            b.setVolunteerId(dto.getVolunteerId());
            b.setScope(dto.getScope());
            b.setDays(dto.getDays());
            b.setReason(reason);
            b.setCreatedBy(adminId);
            b.setCreateTime(LocalDateTime.now());
            banMapper.insert(b);
            sanctionService.impose(dto.getVolunteerId(), VolunteerSanction.SOURCE_SOCIAL_BAN, b.getId(), dto.getScope(), dto.getDays());
            return b.getId();
        });
    }

    /** 提前解除：记下解除人与原因，把这条禁言产生的处置解除掉（CAS：已解除过的再点报错）。 */
    public void lift(Long adminId, Long banId, String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty() || r.length() > 255) {
            throw new BusinessException("请填写解除原因（不超过 255 字）");
        }
        transactionTemplate.executeWithoutResult(s -> {
            int n = banMapper.update(null, Wrappers.<SocialBan>lambdaUpdate()
                    .eq(SocialBan::getId, banId)
                    .isNull(SocialBan::getLiftedTime)
                    .set(SocialBan::getLiftedBy, adminId)
                    .set(SocialBan::getLiftedTime, LocalDateTime.now())
                    .set(SocialBan::getLiftReason, r));
            if (n == 0) {
                throw new BusinessException("禁言记录不存在或已经解除");
            }
            sanctionService.liftBySource(VolunteerSanction.SOURCE_SOCIAL_BAN, banId, adminId, r);
        });
    }

    public PageResult<SocialGovVOs.Ban> list(Long volunteerId, PageQuery query) {
        var wrapper = Wrappers.<SocialBan>lambdaQuery()
                .eq(volunteerId != null, SocialBan::getVolunteerId, volunteerId)
                .orderByDesc(SocialBan::getId);
        Long total = banMapper.selectCount(wrapper);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<SocialBan> rows = banMapper.selectList(wrapper.last("LIMIT " + offset + ", " + query.getSize()));
        Map<Long, SocialVOs.Author> cards = cardService.volunteers(rows.stream().map(SocialBan::getVolunteerId).toList());
        LocalDateTime now = LocalDateTime.now();
        List<SocialGovVOs.Ban> out = rows.stream().map(b -> {
            SocialGovVOs.Ban vo = new SocialGovVOs.Ban();
            vo.setId(b.getId());
            vo.setVolunteerId(b.getVolunteerId());
            vo.setVolunteer(cards.get(b.getVolunteerId()));
            vo.setScope(b.getScope());
            vo.setScopeLabel(SanctionScope.labelOf(b.getScope()));
            vo.setDays(b.getDays());
            vo.setReason(b.getReason());
            vo.setCreatedBy(b.getCreatedBy());
            vo.setCreateTime(b.getCreateTime());
            vo.setExpireTime(b.getCreateTime().plusDays(b.getDays()));
            vo.setActive(b.getLiftedTime() == null && vo.getExpireTime().isAfter(now));
            vo.setLiftedBy(b.getLiftedBy());
            vo.setLiftedTime(b.getLiftedTime());
            vo.setLiftReason(b.getLiftReason());
            return vo;
        }).toList();
        return PageResult.of(out, total == null ? 0 : total, query.getPage(), query.getSize());
    }
}
