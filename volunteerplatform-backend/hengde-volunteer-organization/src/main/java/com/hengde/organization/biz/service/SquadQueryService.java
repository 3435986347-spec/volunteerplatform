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

    /**
     * 启用中的分队名；不存在、已删除或已停用返回 null。供活动「指定分队报名」发布时校验（V4 活动补全批）——
     * 停用的分队志愿者端不可达，把活动限定给它等于谁也报不了。
     */
    public String findEnabledName(Long squadId) {
        if (squadId == null) {
            return null;
        }
        VolunteerSquad squad = squadMapper.selectById(squadId);
        return squad == null || !Integer.valueOf(1).equals(squad.getStatus()) ? null : squad.getName();
    }

    /** 分队总数（逻辑删除由 {@code @TableLogic} 自动排除）。供 data 域数据看板「分队数量」。 */
    public long count() {
        return squadMapper.selectCount(null);
    }
}
