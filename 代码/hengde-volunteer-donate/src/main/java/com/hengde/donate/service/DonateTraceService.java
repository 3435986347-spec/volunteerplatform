package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.donate.dao.DonateItemTraceMapper;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateItemTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 物资流转轨迹（Row 17「以上操作轨迹捐赠人均可在他们的前端看得到记录」）。
 *
 * <p><b>只追加</b>，且<b>必须在业务动作的同一事务里写</b>——轨迹是给捐赠人看的历史，
 * 动作回滚了轨迹却留着，捐赠人会看到一件从没发生的「已送达」。</p>
 *
 * @author hengde
 */
@Service
public class DonateTraceService {

    private static final int CONTENT_MAX = 512;

    private DonateItemTraceMapper traceMapper;

    @Autowired
    public void setTraceMapper(DonateItemTraceMapper traceMapper) {
        this.traceMapper = traceMapper;
    }

    /** 记一条。运单级动作 itemId 传 null。 */
    public void record(Long shipmentId, Long itemId, Long boxId, int action, String content,
                       int operatorType, Long operatorId) {
        DonateItemTrace t = new DonateItemTrace();
        t.setShipmentId(shipmentId);
        t.setItemId(itemId);
        t.setBoxId(boxId);
        t.setAction(action);
        t.setContent(content == null ? "" : (content.length() > CONTENT_MAX ? content.substring(0, CONTENT_MAX) : content));
        t.setOperatorType(operatorType);
        t.setOperatorId(operatorId);
        traceMapper.insert(t);
    }

    /** 每件物资各记一条（装箱送达、核对这类「一次动作、多件物资」的场景）。 */
    public void recordEach(Collection<DonateItem> items, Long boxId, int action, Function<DonateItem, String> content,
                           int operatorType, Long operatorId) {
        for (DonateItem item : items) {
            record(item.getShipmentId(), item.getId(), boxId, action, content.apply(item), operatorType, operatorId);
        }
    }

    /** 一批运单的轨迹，按发生顺序（id 升序）分组。 */
    public Map<Long, List<DonateItemTrace>> listByShipments(Collection<Long> shipmentIds) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return Map.of();
        }
        return traceMapper.selectList(Wrappers.<DonateItemTrace>lambdaQuery()
                        .in(DonateItemTrace::getShipmentId, shipmentIds)
                        .orderByAsc(DonateItemTrace::getId))
                .stream().collect(Collectors.groupingBy(DonateItemTrace::getShipmentId));
    }
}
