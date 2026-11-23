package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.honor.constant.RoleModelLinkType;
import com.hengde.honor.constant.RoleModelType;
import com.hengde.honor.dao.HonorRoleModelMapper;
import com.hengde.honor.dto.RoleModelSaveDTO;
import com.hengde.honor.entity.HonorRoleModel;
import com.hengde.honor.vo.RoleModelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
                .set(HonorRoleModel::getModelType, values.getModelType())
                .set(HonorRoleModel::getSubtitle, values.getSubtitle())
                .set(HonorRoleModel::getSummary, values.getSummary())
                .set(HonorRoleModel::getImageUrl, values.getImageUrl())
                .set(HonorRoleModel::getLinkType, values.getLinkType())
                .set(HonorRoleModel::getLinkUrl, values.getLinkUrl())
                .set(HonorRoleModel::getSort, values.getSort())
                // publish_time 刻意不在这里 set：它归 changeStatus 管（首次上架写一次）。
                // 编辑一条已上架的榜样不该把它的发布时间改成今天——那会让它在小程序
                // 「最新」排序里跳到最前面，而内容其实只是改了个错别字。
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
        HonorRoleModel current = require(id);
        // 「发布时间」＝第一次与志愿者见面的时刻，故只在【首次】上架时写一次：
        // 下架不清空（否则重新上架就丢了原始发布时间），再次上架也不覆盖
        // （临时下架修个错别字再上架，不该让它在小程序「最新」里跳到最前面）。
        boolean firstPublish = status == ON_SHELF && current.getPublishTime() == null;
        roleModelMapper.update(null, Wrappers.<HonorRoleModel>lambdaUpdate()
                .set(HonorRoleModel::getStatus, status)
                .set(firstPublish, HonorRoleModel::getPublishTime, LocalDateTime.now())
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
    /** {@link #listPublished} 的排序方式：按运营排的顺序。 */
    public static final String SORT_DEFAULT = "default";
    /** {@link #listPublished} 的排序方式：按发布时间倒序。 */
    public static final String SORT_LATEST = "latest";

    /**
     * 志愿者端榜样列表：分页 + 关键词 + 类型 + 排序（V43，供小程序触底加载与页签筛选）。
     *
     * <p><b>返回分页对象而不是裸数组</b>：榜样会持续增加，一次全量返回在弱网下越来越慢，
     * 而小程序那边要做触底加载就必须知道 total。此前是裸 {@code List}，
     * 改成 {@link PageResult} 是<b>破坏性改动</b>，已与小程序侧约定同步切换。</p>
     *
     * <p><b>未知的 sort 直接报错而不是悄悄按默认排</b>：拼错一个值就得到另一种顺序、
     * 且没有任何提示，是最难查的那类问题——尤其它「看起来还工作」。</p>
     *
     * @param query     分页
     * @param keyword   关键词，匹配标题/副标题/简介；空则不筛
     * @param modelType 1个人/2团队；空则不筛
     * @param sort      {@link #SORT_DEFAULT}（默认，运营排的顺序）或 {@link #SORT_LATEST}（发布时间倒序）
     * @return 分页结果
     */
    public PageResult<RoleModelVO> listPublished(PageQuery query, String keyword,
                                                 Integer modelType, String sort) {
        if (modelType != null && !RoleModelType.isValid(modelType)) {
            throw new BusinessException("榜样类型非法（1个人/2团队）");
        }
        String order = StringUtils.hasText(sort) ? sort.trim() : SORT_DEFAULT;
        if (!SORT_DEFAULT.equals(order) && !SORT_LATEST.equals(order)) {
            throw new BusinessException("排序方式非法（default 按运营排序 / latest 按发布时间倒序）");
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        Page<HonorRoleModel> page = query.toPage();
        var wrapper = Wrappers.<HonorRoleModel>lambdaQuery()
                .eq(HonorRoleModel::getStatus, ON_SHELF)
                .eq(modelType != null, HonorRoleModel::getModelType, modelType)
                .and(kw != null, w -> w.like(HonorRoleModel::getTitle, kw)
                        .or().like(HonorRoleModel::getSubtitle, kw)
                        .or().like(HonorRoleModel::getSummary, kw));
        if (SORT_LATEST.equals(order)) {
            // publish_time 可能为 NULL（V43 之前就已上架的存量行，它们第一次上架是什么时候
            // 库里没有这个事实）。MySQL 的 DESC 把 NULL 排在最后，正好是想要的——
            // 「不知道什么时候发的」排在「知道」的后面，而不是冒充最新。
            wrapper.orderByDesc(HonorRoleModel::getPublishTime).orderByDesc(HonorRoleModel::getId);
        } else {
            wrapper.orderByAsc(HonorRoleModel::getSort).orderByAsc(HonorRoleModel::getId);
        }
        roleModelMapper.selectPage(page, wrapper);
        return PageResult.of(toVos(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
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
        // 留空按「个人」：后台既有的编辑表单不认识这个字段（V43 才加），
        // 让它落 null 会撞 NOT NULL；默认成「团队」则会让人物事迹跑进团队页签。
        Integer modelType = dto.getModelType() == null ? RoleModelType.PERSON : dto.getModelType();
        if (!RoleModelType.isValid(modelType)) {
            throw new BusinessException("榜样类型非法（1个人/2团队）");
        }
        entity.setModelType(modelType);
        entity.setSubtitle(dto.getSubtitle());
        entity.setSummary(dto.getSummary());
        entity.setImageUrl(dto.getImageUrl());
        applyLink(entity, dto);
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }

    /**
     * 跳转类型与链接一起校验——两者单独看都合法、凑一起才有问题，所以不能拆成两处判断。
     *
     * <p>三条规则，都是「上线才发现」的那类问题，故在保存时就拦下来：</p>
     * <ol>
     *   <li><b>选了跳转就必须给链接</b>：否则小程序拿到一个 linkType=WEB、linkUrl 为空的条目，
     *       只能对着空地址开 WebView。</li>
     *   <li><b>选了「不跳转」却又填了链接：报错，不静默清空</b>。静默清空是最糟的一种——
     *       运营明明填了地址、保存也提示成功，链接却没了，而且没有任何征兆。
     *       宁可当场拒绝，让他把「到底跳不跳」这件事说清楚。</li>
     *   <li><b>WEB 必须是 https</b>：微信业务域名只收 https，http 在体验版能开、正式版白屏。</li>
     * </ol>
     *
     * <p>⚠️ <b>「域名是否已备案」这里判不了</b>——备案清单不在代码里，小程序端配置的
     * 业务域名白名单才是最终裁判。这里只能保证协议对；域名错要到真机上才暴露。</p>
     */
    private void applyLink(HonorRoleModel entity, RoleModelSaveDTO dto) {
        Integer linkType = dto.getLinkType() == null ? RoleModelLinkType.NONE : dto.getLinkType();
        if (!RoleModelLinkType.isValid(linkType)) {
            throw new BusinessException("跳转类型非法（0不跳转/1小程序页面/2网页/3外部链接）");
        }
        String url = dto.getLinkUrl() == null ? null : dto.getLinkUrl().trim();
        if (!RoleModelLinkType.requiresUrl(linkType)) {
            if (StringUtils.hasText(url)) {
                throw new BusinessException("跳转方式选了「不跳转」，就不要再填跳转链接；"
                        + "要保留这个链接请选择对应的打开方式");
            }
            entity.setLinkType(RoleModelLinkType.NONE);
            entity.setLinkUrl(null);
            return;
        }
        if (!StringUtils.hasText(url)) {
            throw new BusinessException("选择了跳转方式就必须填写跳转链接");
        }
        if (linkType == RoleModelLinkType.WEB && !RoleModelLinkType.isHttps(url)) {
            throw new BusinessException("网页跳转必须是 https 链接（微信业务域名不接受 http）");
        }
        entity.setLinkType(linkType);
        entity.setLinkUrl(url);
    }

    private List<RoleModelVO> toVos(List<HonorRoleModel> list) {
        List<RoleModelVO> vos = new ArrayList<>(list.size());
        for (HonorRoleModel e : list) {
            RoleModelVO vo = new RoleModelVO();
            vo.setId(e.getId());
            vo.setTitle(e.getTitle());
            vo.setModelType(e.getModelType());
            vo.setModelTypeLabel(RoleModelType.labelOf(e.getModelType()));
            vo.setSubtitle(e.getSubtitle());
            vo.setSummary(e.getSummary());
            vo.setImageUrl(e.getImageUrl());
            vo.setLinkType(e.getLinkType());
            vo.setLinkTypeName(RoleModelLinkType.nameOf(e.getLinkType()));
            vo.setLinkUrl(e.getLinkUrl());
            vo.setSort(e.getSort());
            vo.setStatus(e.getStatus());
            vo.setPublishTime(e.getPublishTime());
            vo.setCreateTime(e.getCreateTime());
            vos.add(vo);
        }
        return vos;
    }
}
