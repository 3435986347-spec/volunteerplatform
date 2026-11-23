package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.dao.MallVerifierMapper;
import com.hengde.donate.entity.MallVerifier;
import com.hengde.donate.vo.MallVerifierVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商城核销员（Row 8 F「企业可以设置某一个志愿者为企业核销员，企业核销员可以在前端用扫一扫功能给
 * 申请兑换的志愿者核销商品」）。
 *
 * <p><b>V3 由后台指派，企业自助留 V4</b>（V3规划·承重条款 3：enterprise 不在 V3）。
 * 表上已预留 {@code enterprise_id} 作用域，V3 恒为 null = 可核销全部商品。</p>
 *
 * <p><b>核销员资格随账号状态即时失效</b>：账号被停用 / 注销的核销员，即便记录还在，
 * {@link #findActive} 也返回 null——与志愿者端 RBAC「降级即失效」同一口径，
 * 不留「停用但资格仍在」的窗口。</p>
 *
 * @author hengde
 */
@Service
public class MallVerifierService {

    private MallVerifierMapper verifierMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setVerifierMapper(MallVerifierMapper verifierMapper) {
        this.verifierMapper = verifierMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 指派核销员。
     *
     * <p>「一人至多一条有效记录」由生成列唯一键 {@code uk_active_volunteer} 保证，不靠先查再插——
     * 后者并发下必漏（V9 / V28 同一条）。</p>
     *
     * @return 记录 id
     */
    public Long assign(Long volunteerId, String remark, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (volunteerId == null) {
            throw new BusinessException("请选择志愿者");
        }
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("只能指派已实名且账号正常的志愿者为核销员");
        }
        MallVerifier row = new MallVerifier();
        row.setVolunteerId(volunteerId);
        row.setRemark(remark);
        row.setCreateBy(adminId);
        try {
            verifierMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该志愿者已是核销员");
        }
        return row.getId();
    }

    /**
     * 赞助企业指派自己的核销员（V4 爱心企业批，Row 8 F「企业可以设置某一个志愿者为企业核销员」）：按手机号找人，
     * 作用域记企业 id——<b>只能核销本企业赞助的商品</b>（判定在 {@code MallOrderService.doVerify}）。一个志愿者同时只能是一处的核销员。
     */
    public Long assignForEnterprise(Long enterpriseId, String phone, String remark) {
        if (enterpriseId == null) {
            throw new BusinessException("企业不能为空");
        }
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException("请填写核销员的手机号");
        }
        Long volunteerId = volunteerQueryService.findIdsByPhones(List.of(phone.trim())).get(phone.trim());
        if (volunteerId == null || !volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("没有找到这个手机号的已实名志愿者");
        }
        MallVerifier row = new MallVerifier();
        row.setVolunteerId(volunteerId);
        row.setEnterpriseId(enterpriseId);
        row.setRemark(remark);
        try {
            verifierMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该志愿者已是核销员（平台或其他企业的），请先撤销原来的");
        }
        return row.getId();
    }

    /** 赞助企业的核销员列表（姓名只留姓）。 */
    public PageResult<MallVerifierVO> listForEnterprise(Long enterpriseId, PageQuery query) {
        IPage<MallVerifier> page = verifierMapper.selectPage(query.toPage(), Wrappers.<MallVerifier>lambdaQuery()
                .eq(MallVerifier::getEnterpriseId, enterpriseId)
                .orderByDesc(MallVerifier::getId));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(page.getRecords().stream()
                .map(MallVerifier::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(v -> {
            MallVerifierVO vo = new MallVerifierVO();
            vo.setId(v.getId());
            vo.setVolunteerId(v.getVolunteerId());
            vo.setVolunteerName(com.hengde.common.utils.MaskUtil.maskName(names.get(v.getVolunteerId())));
            vo.setEnterpriseId(v.getEnterpriseId());
            vo.setRemark(v.getRemark());
            vo.setCreateTime(v.getCreateTime());
            return vo;
        }));
    }

    /** 赞助企业撤销自己的核销员（只能撤本企业的）。 */
    public void removeForEnterprise(Long id, Long enterpriseId) {
        if (id == null || enterpriseId == null || verifierMapper.delete(Wrappers.<MallVerifier>lambdaQuery()
                .eq(MallVerifier::getId, id).eq(MallVerifier::getEnterpriseId, enterpriseId)) != 1) {
            throw new BusinessException("核销员记录不存在");
        }
    }

    /** 撤销核销员资格（逻辑删除；软删行不占唯一键，之后可重新指派）。 */
    public void remove(Long id) {
        if (id == null || verifierMapper.deleteById(id) != 1) {
            throw new BusinessException("核销员记录不存在");
        }
    }

    /** 核销员列表（管理端）。姓名批量换取，不逐行查。 */
    public PageResult<MallVerifierVO> list(PageQuery query) {
        IPage<MallVerifier> page = verifierMapper.selectPage(query.toPage(),
                Wrappers.<MallVerifier>lambdaQuery().orderByDesc(MallVerifier::getId));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(page.getRecords().stream()
                .map(MallVerifier::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(v -> {
            MallVerifierVO vo = new MallVerifierVO();
            vo.setId(v.getId());
            vo.setVolunteerId(v.getVolunteerId());
            vo.setVolunteerName(names.get(v.getVolunteerId()));
            vo.setEnterpriseId(v.getEnterpriseId());
            vo.setRemark(v.getRemark());
            vo.setCreateBy(v.getCreateBy());
            vo.setCreateTime(v.getCreateTime());
            return vo;
        }));
    }

    /**
     * 该志愿者当前有效的核销员记录；不是核销员、或账号已停用 / 注销，一律返回 null。
     *
     * <p>小程序据 {@link #isVerifier} 决定是否显示「扫一扫核销」入口（仅 UX），
     * 核销接口本身再用本方法兜底——入口藏起来不等于接口挡住了。</p>
     */
    public MallVerifier findActive(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        MallVerifier row = verifierMapper.selectOne(Wrappers.<MallVerifier>lambdaQuery()
                .eq(MallVerifier::getVolunteerId, volunteerId));
        if (row == null || !volunteerQueryService.isActive(volunteerId)) {
            return null;
        }
        return row;
    }

    public boolean isVerifier(Long volunteerId) {
        return findActive(volunteerId) != null;
    }
}
