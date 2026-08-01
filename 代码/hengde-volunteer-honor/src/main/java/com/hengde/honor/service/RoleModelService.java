package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.honor.dao.HonorRoleModelMapper;
import com.hengde.honor.dto.RoleModelSaveDTO;
import com.hengde.honor.entity.HonorRoleModel;
import com.hengde.honor.vo.RoleModelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 榜样管理：标题 / 副标题 / 图片 / 跳转链接 / 排序 / 上下架。
 *
 * <p>数据形态与 {@code publicity_banner} 同构，行为也刻意保持一致（新增落<b>下架</b>态、
 * 上架是独立动作、志愿者端只看已上架）——榜样和轮播图在后台是同一种心智模型，
 * 没必要让运营为了两个几乎一样的东西记两套规则。</p>
 *
 * <p>榜样<b>不走审核</b>：需求里的「审核」是针对勋章样式与发放说的，
 * 榜样与公告/轮播图同级，由有权限的运营直接维护，上下架本身就是发布闸门。</p>
 *
 * @author hengde
 */
@Service
public class RoleModelService {

    /** 状态：下架 */
    public static final int OFF_SHELF = 0;
    /** 状态：上架 */
    public static final int ON_SHELF = 1;

    private HonorRoleModelMapper roleModelMapper;

    @Autowired
    public void setRoleModelMapper(HonorRoleModelMapper roleModelMapper) {
        this.roleModelMapper = roleModelMapper;
    }

    /**
     * 新增，落<b>下架</b>态。
     *
     * <p>新建即上架会让还没填完图片的条目直接出现在志愿者端；下架态给运营一个校对的机会。</p>
     *
     * @param dto 入参
     * @return 新建 id
     */
    public Long create(RoleModelSaveDTO dto) {
        HonorRoleModel entity = new HonorRoleModel();
        applyDto(entity, dto);
        entity.setStatus(OFF_SHELF);
        roleModelMapper.insert(entity);
        return entity.getId();
    }

    /**
     * 修改。上下架状态不在此变更，见 {@link #changeStatus}。
     *
     * @param id  id
     * @param dto 入参
     */
    public void update(Long id, RoleModelSaveDTO dto) {
        require(id);
        HonorRoleModel values = new HonorRoleModel();
        applyDto(values, dto);
        // 显式 set 而非 updateById：副标题/图片/链接都可以被清空，
        // 而 MP 默认跳过 null 字段——那样「删掉副标题」保存后它还在。
        roleModelMapper.update(null, Wrappers.<HonorRoleModel>lambdaUpdate()
                .eq(HonorRoleModel::getId, id)
                .set(HonorRoleModel::getTitle, values.getTitle())
                .set(HonorRoleModel::getSubtitle, values.getSubtitle())
                .set(HonorRoleModel::getImageUrl, values.getImageUrl())
                .set(HonorRoleModel::getLinkUrl, values.getLinkUrl())
                .set(HonorRoleModel::getSort, values.getSort())
                .set(HonorRoleModel::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 上架 / 下架。
     *
     * @param id     id
     * @param status 0下架/1上架
     */
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != OFF_SHELF && status != ON_SHELF)) {
            throw new BusinessException("状态非法（0下架/1上架）");
        }
        require(id);
        roleModelMapper.update(null, Wrappers.<HonorRoleModel>lambdaUpdate()
                .set(HonorRoleModel::getStatus, status)
                .set(HonorRoleModel::getUpdateTime, LocalDateTime.now())
                .eq(HonorRoleModel::getId, id));
    }

    /**
     * 调整排序。
     *
     * @param id   id
     * @param sort 排序值
     */
    public void updateSort(Long id, Integer sort) {
        if (sort == null) {
            throw new BusinessException("排序值不能为空");
        }
        require(id);
        roleModelMapper.update(null, Wrappers.<HonorRoleModel>lambdaUpdate()
                .set(HonorRoleModel::getSort, sort)
                .set(HonorRoleModel::getUpdateTime, LocalDateTime.now())
                .eq(HonorRoleModel::getId, id));
    }

    /**
     * 删除（逻辑删除）。
     *
     * @param id id
     */
    public void delete(Long id) {
        require(id);
        roleModelMapper.deleteById(id);
    }

    /**
     * 后台列表。
     *
     * @param status 状态筛选；null=全部
     * @return 按 sort、id 正序
     */
    public List<RoleModelVO> listForAdmin(Integer status) {
        return toVos(roleModelMapper.selectList(Wrappers.<HonorRoleModel>lambdaQuery()
                .eq(status != null, HonorRoleModel::getStatus, status)
                .orderByAsc(HonorRoleModel::getSort)
                .orderByAsc(HonorRoleModel::getId)));
    }

    /**
     * 志愿者端列表：<b>仅已上架</b>。
     *
     * @return 按 sort、id 正序
     */
    public List<RoleModelVO> listPublished() {
        return toVos(roleModelMapper.selectList(Wrappers.<HonorRoleModel>lambdaQuery()
                .eq(HonorRoleModel::getStatus, ON_SHELF)
                .orderByAsc(HonorRoleModel::getSort)
                .orderByAsc(HonorRoleModel::getId)));
    }

    // ---------- helpers ----------

    private HonorRoleModel require(Long id) {
        HonorRoleModel entity = id == null ? null : roleModelMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("榜样不存在");
        }
        return entity;
    }

    private void applyDto(HonorRoleModel entity, RoleModelSaveDTO dto) {
        entity.setTitle(dto.getTitle() == null ? null : dto.getTitle().trim());
        entity.setSubtitle(dto.getSubtitle());
        entity.setImageUrl(dto.getImageUrl());
        entity.setLinkUrl(dto.getLinkUrl());
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }

    private List<RoleModelVO> toVos(List<HonorRoleModel> list) {
        List<RoleModelVO> vos = new ArrayList<>(list.size());
        for (HonorRoleModel e : list) {
            RoleModelVO vo = new RoleModelVO();
            vo.setId(e.getId());
            vo.setTitle(e.getTitle());
            vo.setSubtitle(e.getSubtitle());
            vo.setImageUrl(e.getImageUrl());
            vo.setLinkUrl(e.getLinkUrl());
            vo.setSort(e.getSort());
            vo.setStatus(e.getStatus());
            vo.setCreateTime(e.getCreateTime());
            vos.add(vo);
        }
        return vos;
    }
}
