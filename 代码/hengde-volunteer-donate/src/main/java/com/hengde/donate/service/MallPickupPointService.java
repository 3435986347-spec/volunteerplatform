package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.donate.dao.MallPickupPointMapper;
import com.hengde.donate.dto.MallPickupPointDTO;
import com.hengde.donate.entity.MallPickupPoint;
import com.hengde.donate.vo.MallPickupPointVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 积分商城自提点（V94，协会答复五 丙-3）。后台维护；商品选它在哪几个自提点可领，志愿者兑换时选一个。
 *
 * <p>商品上存的是 id 列表（{@code mall_goods.pickup_point_ids}，逗号分隔），这里负责它的解析、校验与展示，
 * 免得商品服务与兑换服务各写一份「哪些 id 还有效」。</p>
 *
 * @author hengde
 */
@Service
public class MallPickupPointService {

    /** 一件商品最多挂多少个自提点（列宽 255，按每个 id 不超过 10 位算留足余量） */
    static final int MAX_PER_GOODS = 20;

    private MallPickupPointMapper pointMapper;

    @Autowired
    public void setPointMapper(MallPickupPointMapper pointMapper) {
        this.pointMapper = pointMapper;
    }

    /** 后台列表（含停用的）。 */
    public List<MallPickupPointVO> listAll() {
        return pointMapper.selectList(Wrappers.<MallPickupPoint>lambdaQuery()
                        .orderByAsc(MallPickupPoint::getSort).orderByAsc(MallPickupPoint::getId))
                .stream().map(MallPickupPointService::toVO).toList();
    }

    public Long create(MallPickupPointDTO dto) {
        MallPickupPoint p = new MallPickupPoint();
        fill(p, dto);
        pointMapper.insert(p);
        return p.getId();
    }

    /** 修改：显式 set（含 null）——电话、开放时间要能真的清掉，updateById 会跳过 null。 */
    public void update(Long id, MallPickupPointDTO dto) {
        MallPickupPoint p = new MallPickupPoint();
        fill(p, dto);
        int rows = pointMapper.update(null, Wrappers.<MallPickupPoint>lambdaUpdate()
                .eq(MallPickupPoint::getId, id)
                .set(MallPickupPoint::getName, p.getName())
                .set(MallPickupPoint::getAddress, p.getAddress())
                .set(MallPickupPoint::getPhone, p.getPhone())
                .set(MallPickupPoint::getOpenHours, p.getOpenHours())
                .set(MallPickupPoint::getEnabled, p.getEnabled())
                .set(MallPickupPoint::getSort, p.getSort())
                .set(MallPickupPoint::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("自提点不存在");
        }
    }

    /**
     * 删除（逻辑删除）。<b>不连带改商品</b>：商品上残留的这个 id 在展示与兑换时都会被当作无效滤掉
     * （{@link #usableByIds}），历史兑换单有快照不受影响。
     */
    public void delete(Long id) {
        if (id == null || pointMapper.deleteById(id) != 1) {
            throw new BusinessException("自提点不存在");
        }
    }

    /** 启用中的那些（按商品上的顺序）；不存在 / 已停用 / 已删除的静默滤掉——它们只影响展示，不该让整件商品不可读。 */
    public List<MallPickupPointVO> usableByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, MallPickupPoint> byId = pointMapper.selectBatchIds(ids).stream()
                .filter(p -> p.getEnabled() != null && p.getEnabled() == 1)
                .collect(Collectors.toMap(MallPickupPoint::getId, Function.identity()));
        List<MallPickupPointVO> out = new ArrayList<>();
        for (Long id : ids) {
            MallPickupPoint p = byId.get(id);
            if (p != null) {
                out.add(toVO(p));
            }
        }
        return out;
    }

    /**
     * 商品保存时校验所选自提点：去重、都存在且启用、不超过上限。
     *
     * @return 规范化后的逗号串；空列表返回 null
     */
    public String requireUsableIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        unique.remove(null);
        if (unique.size() > MAX_PER_GOODS) {
            throw new BusinessException("一件商品最多选择 " + MAX_PER_GOODS + " 个自提点");
        }
        Set<Long> usable = usableByIds(unique).stream().map(MallPickupPointVO::getId).collect(Collectors.toSet());
        for (Long id : unique) {
            if (!usable.contains(id)) {
                throw new BusinessException("自提点不存在或已停用：" + id);
            }
        }
        return unique.isEmpty() ? null : unique.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 逗号串 → id 列表（坏的段静默跳过）。 */
    public static List<Long> parseIds(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException ignored) {
                // 列只由 requireUsableIds 写入，坏段只可能来自手工改库
            }
        }
        return out;
    }

    /** 兑换时取志愿者选的那个：必须在商品给出的列表里、且仍启用。 */
    public MallPickupPoint requireChoosable(String goodsPointIds, Long chosen) {
        if (chosen == null) {
            throw new BusinessException("请选择自提点");
        }
        if (!parseIds(goodsPointIds).contains(chosen)) {
            throw new BusinessException("该商品不能在这个自提点领取");
        }
        MallPickupPoint p = pointMapper.selectById(chosen);
        if (p == null || p.getEnabled() == null || p.getEnabled() != 1) {
            throw new BusinessException("这个自提点已停用，请换一个");
        }
        return p;
    }

    private static void fill(MallPickupPoint p, MallPickupPointDTO dto) {
        if (dto.getEnabled() != null && dto.getEnabled() != 0 && dto.getEnabled() != 1) {
            throw new BusinessException("启用标记只能是 0 或 1");
        }
        p.setName(dto.getName().trim());
        p.setAddress(dto.getAddress().trim());
        p.setPhone(StringUtils.hasText(dto.getPhone()) ? dto.getPhone().trim() : null);
        p.setOpenHours(StringUtils.hasText(dto.getOpenHours()) ? dto.getOpenHours().trim() : null);
        p.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        p.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }

    static MallPickupPointVO toVO(MallPickupPoint p) {
        MallPickupPointVO vo = new MallPickupPointVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setAddress(p.getAddress());
        vo.setPhone(p.getPhone());
        vo.setOpenHours(p.getOpenHours());
        vo.setEnabled(p.getEnabled());
        vo.setSort(p.getSort());
        return vo;
    }
}
