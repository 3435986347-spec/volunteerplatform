package com.hengde.organization.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.organization.exam.dao.OrgTempLeaderQualificationMapper;
import com.hengde.organization.exam.entity.OrgTempLeaderQualification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「活动临时负责人」身份的只读判定（跨模块：activity 报名窗口 / 名单公示排序，user「所属职务」）。
 *
 * <p><b>按时间现算</b>（V4规划 D4）：没撤销、且不设期限或还没到期。不靠定时任务改状态位——漏跑一次，过期的人就一直是临时负责人。
 * 「是不是临时负责人」这条语义只在这里说，别的模块不要自己去拼这张表的条件。</p>
 *
 * @author hengde
 */
@Service
public class TempLeaderQueryService {

    private OrgTempLeaderQualificationMapper qualificationMapper;

    @Autowired
    public void setQualificationMapper(OrgTempLeaderQualificationMapper qualificationMapper) {
        this.qualificationMapper = qualificationMapper;
    }

    public boolean isTempLeader(Long volunteerId) {
        return volunteerId != null && current(volunteerId) != null;
    }

    /** 这个人现在有效的那一条资格；没有为 null。 */
    public OrgTempLeaderQualification current(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return qualificationMapper.selectOne(Wrappers.<OrgTempLeaderQualification>lambdaQuery()
                .eq(OrgTempLeaderQualification::getVolunteerId, volunteerId)
                .isNull(OrgTempLeaderQualification::getRevokedTime)
                .and(w -> w.isNull(OrgTempLeaderQualification::getExpireTime)
                        .or().gt(OrgTempLeaderQualification::getExpireTime, now))
                .last("LIMIT 1"));
    }

    /** 从一批志愿者里筛出现在是临时负责人的，一次查库。 */
    public Set<Long> filterTempLeaders(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Set.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<OrgTempLeaderQualification> rows = qualificationMapper.selectList(Wrappers.<OrgTempLeaderQualification>lambdaQuery()
                .select(OrgTempLeaderQualification::getVolunteerId)
                .in(OrgTempLeaderQualification::getVolunteerId, new HashSet<>(volunteerIds))
                .isNull(OrgTempLeaderQualification::getRevokedTime)
                .and(w -> w.isNull(OrgTempLeaderQualification::getExpireTime)
                        .or().gt(OrgTempLeaderQualification::getExpireTime, now)));
        Set<Long> out = new HashSet<>();
        for (OrgTempLeaderQualification q : rows) {
            out.add(q.getVolunteerId());
        }
        return out;
    }
}
