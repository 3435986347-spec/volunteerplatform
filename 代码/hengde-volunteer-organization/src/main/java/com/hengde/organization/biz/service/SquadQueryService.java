package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerSquad;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分队只读出参：供其他领域（如 user 域志愿者管理）按 squadId 批量取分队名展示，不暴露 mapper/entity。
 *
 * <p>与 {@link GroupQueryService}（小组）分列，二者领域语义不同（分队=归属、小组=自组织）。纯读不写。</p>
 *
 * @author hengde
 */
@Service
public class SquadQueryService {

    private VolunteerSquadMapper squadMapper;

    @Autowired
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    /**
     * 批量取分队名（squadId → name），供志愿者列表/详情展示「归属分队」。一次查库，避免 N+1。
     *
     * @param squadIds 分队 id 集合（可含 null，会被过滤）
     * @return id -> 分队名；空集合或全为 null 返回空 Map
     */
    public Map<Long, String> listNamesByIds(Collection<Long> squadIds) {
        if (squadIds == null || squadIds.isEmpty()) {
            return Map.of();
        }
        HashSet<Long> ids = new HashSet<>(squadIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return squadMapper.selectList(Wrappers.<VolunteerSquad>lambdaQuery()
                        .select(VolunteerSquad::getId, VolunteerSquad::getName)
                        .in(VolunteerSquad::getId, ids))
                .stream().collect(Collectors.toMap(VolunteerSquad::getId, VolunteerSquad::getName));
    }
}
