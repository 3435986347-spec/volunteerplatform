package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dao.DonatePairMappers.DonatePairProjectMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairRecordMapper;
import com.hengde.donate.entity.DonatePairProject;
import com.hengde.donate.entity.DonatePairRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 结对域的<b>跨模块只读窄接口</b>——给 honor 出捐赠证书用。
 *
 * <p>形状仿 activity 的 {@code ActivityCertificateQueryService}：
 * <b>「什么算结对成立、证书上该印什么」这些结对域语义收在本服务内</b>，不外泄给 honor 去拼条件，
 * 否则口径会和结对中心漂开。honor 只负责发证、渲染、存文件。</p>
 *
 * <p>依赖方向 {@code honor → donate} 自 V3 微心愿批起成立（微心愿排行的数据源也在 donate），
 * 故这里是正向依赖、不成环。</p>
 *
 * @author hengde
 */
@Service
public class DonatePairQueryService {

    /**
     * 出证所需的事实。<b>只有「结对成立」的登记才有</b>——没成立就不该有证书，
     * 这道闸门放在这里而不是某个入口：honor 那边无论从事件来、还是日后从补偿任务来，走的都是这一条。
     */
    public record PairCertificateSubject(Long volunteerId,
                                         Long projectId,
                                         String projectTitle,
                                         String projectTypeLabel,
                                         BigDecimal amount,
                                         LocalDateTime establishedTime) {
    }

    private DonatePairRecordMapper recordMapper;
    private DonatePairProjectMapper projectMapper;

    @Autowired
    public void setRecordMapper(DonatePairRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setProjectMapper(DonatePairProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    /**
     * 取出证事实；<b>登记不存在、已取消、还没成立，一律返回 null</b>（由调用方决定怎么报）。
     *
     * @param pairRecordId 结对登记 id
     */
    public PairCertificateSubject findEstablishedSubject(Long pairRecordId) {
        DonatePairRecord r = pairRecordId == null ? null : recordMapper.selectById(pairRecordId);
        if (r == null || !Objects.equals(r.getStatus(), PairFlow.PAIR_ESTABLISHED)) {
            return null;
        }
        DonatePairProject p = projectMapper.selectById(r.getProjectId());
        if (p == null) {
            return null;
        }
        return new PairCertificateSubject(r.getVolunteerId(), p.getId(), p.getTitle(),
                PairFlow.typeLabel(p.getProjectType()), r.getAmount(), r.getEstablishedTime());
    }

    /**
     * 批量取「这些结对登记各自属于哪个项目」，给证书列表展示用（一次查完，不按行循环）。
     *
     * @return 结对登记 id → 项目名
     */
    public Map<Long, String> projectTitlesByPairIds(Collection<Long> pairRecordIds) {
        if (pairRecordIds == null || pairRecordIds.isEmpty()) {
            return Map.of();
        }
        List<DonatePairRecord> records = recordMapper.selectList(Wrappers.<DonatePairRecord>lambdaQuery()
                .select(DonatePairRecord::getId, DonatePairRecord::getProjectId)
                .in(DonatePairRecord::getId, pairRecordIds));
        if (records.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = projectMapper.selectList(Wrappers.<DonatePairProject>lambdaQuery()
                        .select(DonatePairProject::getId, DonatePairProject::getTitle)
                        .in(DonatePairProject::getId, records.stream()
                                .map(DonatePairRecord::getProjectId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(DonatePairProject::getId, DonatePairProject::getTitle,
                        (a, b) -> a));
        Map<Long, String> out = new HashMap<>();
        for (DonatePairRecord r : records) {
            String title = titles.get(r.getProjectId());
            if (title != null) {
                out.put(r.getId(), title);
            }
        }
        return out;
    }
}
