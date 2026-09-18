package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateBarcodeCatalogMapper;
import com.hengde.donate.dao.DonateRecipientOrgMapper;
import com.hengde.donate.dto.BarcodeCatalogSaveDTO;
import com.hengde.donate.dto.RecipientOrgSaveDTO;
import com.hengde.donate.entity.DonateBarcodeCatalog;
import com.hengde.donate.entity.DonateRecipientOrg;
import com.hengde.donate.vo.DonateMasterVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 捐书批的两类主数据：受赠单位（清单 ⑤ 默认的主数据表）与商品条码库（Row 17 D）。
 *
 * <p>唯一性一律靠生成列唯一键（{@code uk_active_name} / {@code uk_active_barcode}），
 * 不靠「先查有没有再插」——后者并发下必漏；软删的行不占键，删了可以重建（V31 那一课）。</p>
 *
 * @author hengde
 */
@Service
public class DonateMasterDataService {

    public static final int ORG_ENABLED = 1;
    public static final int ORG_DISABLED = 0;

    private DonateRecipientOrgMapper orgMapper;
    private DonateBarcodeCatalogMapper catalogMapper;

    @Autowired
    public void setOrgMapper(DonateRecipientOrgMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    @Autowired
    public void setCatalogMapper(DonateBarcodeCatalogMapper catalogMapper) {
        this.catalogMapper = catalogMapper;
    }

    // ================= 受赠单位 =================

    public Long createOrg(RecipientOrgSaveDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        DonateRecipientOrg org = new DonateRecipientOrg();
        applyOrg(org, dto);
        org.setCreateBy(adminId);
        try {
            orgMapper.insert(org);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该单位名称已存在");
        }
        return org.getId();
    }

    public void updateOrg(Long id, RecipientOrgSaveDTO dto) {
        DonateRecipientOrg values = new DonateRecipientOrg();
        applyOrg(values, dto);
        int rows;
        try {
            rows = orgMapper.update(null, Wrappers.<DonateRecipientOrg>lambdaUpdate()
                    .eq(DonateRecipientOrg::getId, id)
                    .set(DonateRecipientOrg::getName, values.getName())
                    .set(DonateRecipientOrg::getOrgType, values.getOrgType())
                    .set(DonateRecipientOrg::getAddress, values.getAddress())
                    .set(DonateRecipientOrg::getContactName, values.getContactName())
                    .set(DonateRecipientOrg::getContactPhone, values.getContactPhone())
                    .set(DonateRecipientOrg::getStatus, values.getStatus())
                    .set(DonateRecipientOrg::getSort, values.getSort())
                    .set(DonateRecipientOrg::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该单位名称已存在");
        }
        if (rows != 1) {
            throw new BusinessException("受赠单位不存在");
        }
    }

    /** 删除（逻辑删除）。已送达的物资与箱子存的是单位名<b>快照</b>，删掉单位不会让历史记录变空。 */
    public void deleteOrg(Long id) {
        if (id == null || orgMapper.deleteById(id) != 1) {
            throw new BusinessException("受赠单位不存在");
        }
    }

    public PageResult<DonateMasterVOs.RecipientOrg> listOrgs(PageQuery query, String keyword, Integer status) {
        IPage<DonateRecipientOrg> page = orgMapper.selectPage(query.toPage(), Wrappers.<DonateRecipientOrg>lambdaQuery()
                .eq(status != null, DonateRecipientOrg::getStatus, status)
                .like(StringUtils.hasText(keyword), DonateRecipientOrg::getName, keyword)
                .orderByAsc(DonateRecipientOrg::getSort)
                .orderByDesc(DonateRecipientOrg::getId));
        return PageResult.of(page.convert(DonateMasterDataService::toOrgVO));
    }

    /** 导入微心愿时按<b>名称</b>对上主数据（只认启用的）。返回 名称 → 单位。 */
    public Map<String, DonateRecipientOrg> findEnabledOrgsByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Map.of();
        }
        return orgMapper.selectList(Wrappers.<DonateRecipientOrg>lambdaQuery()
                        .in(DonateRecipientOrg::getName, names)
                        .eq(DonateRecipientOrg::getStatus, ORG_ENABLED))
                .stream().collect(Collectors.toMap(DonateRecipientOrg::getName, o -> o, (a, b) -> a));
    }

    /** 送达时选单位：必须存在且启用。 */
    public DonateRecipientOrg requireEnabledOrg(Long id) {
        DonateRecipientOrg org = id == null ? null : orgMapper.selectById(id);
        if (org == null) {
            throw new BusinessException("受赠单位不存在");
        }
        if (org.getStatus() == null || org.getStatus() != ORG_ENABLED) {
            throw new BusinessException("该受赠单位已停用");
        }
        return org;
    }

    private static void applyOrg(DonateRecipientOrg org, RecipientOrgSaveDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getName())) {
            throw new BusinessException("请填写单位名称");
        }
        int type = dto.getOrgType() == null ? 1 : dto.getOrgType();
        if (type < 1 || type > 3) {
            throw new BusinessException("单位类型只能是 1 学校 / 2 乡镇 / 3 其他");
        }
        int status = dto.getStatus() == null ? ORG_ENABLED : dto.getStatus();
        if (status != ORG_ENABLED && status != ORG_DISABLED) {
            throw new BusinessException("状态只能是 1（启用）或 0（停用）");
        }
        org.setName(dto.getName().trim());
        org.setOrgType(type);
        org.setAddress(dto.getAddress());
        org.setContactName(dto.getContactName());
        org.setContactPhone(dto.getContactPhone());
        org.setStatus(status);
        org.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }

    private static DonateMasterVOs.RecipientOrg toOrgVO(DonateRecipientOrg o) {
        DonateMasterVOs.RecipientOrg vo = new DonateMasterVOs.RecipientOrg();
        vo.setId(o.getId());
        vo.setName(o.getName());
        vo.setOrgType(o.getOrgType());
        vo.setAddress(o.getAddress());
        vo.setContactName(o.getContactName());
        vo.setContactPhone(o.getContactPhone());
        vo.setStatus(o.getStatus());
        vo.setSort(o.getSort());
        vo.setCreateTime(o.getCreateTime());
        return vo;
    }

    // ================= 商品条码库 =================

    public Long createCatalog(BarcodeCatalogSaveDTO dto) {
        DonateBarcodeCatalog c = new DonateBarcodeCatalog();
        applyCatalog(c, dto);
        try {
            catalogMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该条码已在条码库中");
        }
        return c.getId();
    }

    public void updateCatalog(Long id, BarcodeCatalogSaveDTO dto) {
        DonateBarcodeCatalog values = new DonateBarcodeCatalog();
        applyCatalog(values, dto);
        int rows;
        try {
            rows = catalogMapper.update(null, Wrappers.<DonateBarcodeCatalog>lambdaUpdate()
                    .eq(DonateBarcodeCatalog::getId, id)
                    .set(DonateBarcodeCatalog::getBarcode, values.getBarcode())
                    .set(DonateBarcodeCatalog::getName, values.getName())
                    .set(DonateBarcodeCatalog::getItemType, values.getItemType())
                    .set(DonateBarcodeCatalog::getSpec, values.getSpec())
                    .set(DonateBarcodeCatalog::getRemark, values.getRemark())
                    .set(DonateBarcodeCatalog::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该条码已在条码库中");
        }
        if (rows != 1) {
            throw new BusinessException("条码库条目不存在");
        }
    }

    public void deleteCatalog(Long id) {
        if (id == null || catalogMapper.deleteById(id) != 1) {
            throw new BusinessException("条码库条目不存在");
        }
    }

    public PageResult<DonateMasterVOs.Catalog> listCatalog(PageQuery query, String keyword) {
        IPage<DonateBarcodeCatalog> page = catalogMapper.selectPage(query.toPage(),
                Wrappers.<DonateBarcodeCatalog>lambdaQuery()
                        .and(StringUtils.hasText(keyword), w -> w.eq(DonateBarcodeCatalog::getBarcode, keyword.trim())
                                .or().like(DonateBarcodeCatalog::getName, keyword))
                        .orderByDesc(DonateBarcodeCatalog::getId));
        return PageResult.of(page.convert(DonateMasterDataService::toCatalogVO));
    }

    /**
     * 按条码查（Row 17 D「捐赠人在商品里面输入那本书，即可跳出那本书的信息，让他们核对」）。
     *
     * <p><b>查不到返回 null、不抛错</b>：条码库不可能收全天下的书，查不到就让捐赠人手填，
     * 这是正常流程而不是错误。</p>
     */
    public DonateMasterVOs.Catalog lookup(String barcode) {
        if (!StringUtils.hasText(barcode)) {
            return null;
        }
        DonateBarcodeCatalog c = catalogMapper.selectOne(Wrappers.<DonateBarcodeCatalog>lambdaQuery()
                .eq(DonateBarcodeCatalog::getBarcode, barcode.trim()));
        return c == null ? null : toCatalogVO(c);
    }

    private static void applyCatalog(DonateBarcodeCatalog c, BarcodeCatalogSaveDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getBarcode()) || !StringUtils.hasText(dto.getName())) {
            throw new BusinessException("请填写条码与商品名");
        }
        int type = dto.getItemType() == null ? DonateFlow.TYPE_BOOK : dto.getItemType();
        if (!DonateFlow.isValidItemType(type)) {
            throw new BusinessException("物资类型只能是 1 课外书籍 / 2 学习用品 / 3 运动器材 / 9 其他");
        }
        c.setBarcode(dto.getBarcode().trim());
        c.setName(dto.getName().trim());
        c.setItemType(type);
        c.setSpec(dto.getSpec());
        c.setRemark(dto.getRemark());
    }

    private static DonateMasterVOs.Catalog toCatalogVO(DonateBarcodeCatalog c) {
        DonateMasterVOs.Catalog vo = new DonateMasterVOs.Catalog();
        vo.setId(c.getId());
        vo.setBarcode(c.getBarcode());
        vo.setName(c.getName());
        vo.setItemType(c.getItemType());
        vo.setItemTypeLabel(DonateFlow.itemTypeLabel(c.getItemType()));
        vo.setSpec(c.getSpec());
        vo.setRemark(c.getRemark());
        return vo;
    }

    /** 给扫码识别用：条码库里有没有这个码。 */
    public DonateBarcodeCatalog findCatalog(String barcode) {
        return !StringUtils.hasText(barcode) ? null : catalogMapper.selectOne(
                Wrappers.<DonateBarcodeCatalog>lambdaQuery().eq(DonateBarcodeCatalog::getBarcode, barcode.trim()));
    }

    /** 启用中的受赠单位（送达时下拉选）。 */
    public List<DonateMasterVOs.RecipientOrg> listEnabledOrgs() {
        return orgMapper.selectList(Wrappers.<DonateRecipientOrg>lambdaQuery()
                        .eq(DonateRecipientOrg::getStatus, ORG_ENABLED)
                        .orderByAsc(DonateRecipientOrg::getSort)
                        .orderByDesc(DonateRecipientOrg::getId))
                .stream().map(DonateMasterDataService::toOrgVO).toList();
    }
}
