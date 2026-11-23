package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.honor.constant.MedalConditionType;
import com.hengde.honor.constant.MedalGrantStatus;
import com.hengde.honor.constant.MedalStatus;
import com.hengde.honor.dao.HonorMedalGrantMapper;
import com.hengde.honor.dao.HonorMedalMapper;
import com.hengde.honor.dto.MedalSaveDTO;
import com.hengde.honor.entity.HonorMedal;
import com.hengde.honor.entity.HonorMedalGrant;
import com.hengde.honor.vo.MedalVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 勋章定义（样式）管理：录入 → 提交 → 样式审核 → 已启用。
 *
 * <p><b>为什么样式要审核</b>：需求原文「勋章样式、上传、审核、发放，均由后台审核」。
 * 落点是——只有 {@link MedalStatus#ENABLED} 的勋章可以发放（校验在
 * {@link MedalGrantService#apply}），未过审的样式发不出去。</p>
 *
 * <p><b>所有状态流转一律 CAS 条件更新</b>（{@code update ... where status = 期望值}），
 * 与 V14 考勤变更审核、V19 活动发布审核同一模式：并发下两个审核员同时点「通过」，
 * 只有一次会 affected=1，另一次拿到 0 行并报错，不会重复写审核痕迹。</p>
 *
 * @author hengde
 */
@Service
public class MedalService {

    private HonorMedalMapper medalMapper;
    private HonorMedalGrantMapper grantMapper;

    @Autowired
    public void setMedalMapper(HonorMedalMapper medalMapper) {
        this.medalMapper = medalMapper;
    }

    @Autowired
    public void setGrantMapper(HonorMedalGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    // ================= 定义维护 =================

    /**
     * 新增勋章定义，落<b>草稿</b>。
     *
     * @param dto 定义
     * @return 新建的勋章 id
     */
    public Long create(MedalSaveDTO dto) {
        HonorMedal medal = new HonorMedal();
        applyDto(medal, dto);
        medal.setStatus(MedalStatus.DRAFT);
        medalMapper.insert(medal);
        return medal.getId();
    }

    /**
     * 修改勋章定义。
     *
     * <p><b>改了已启用的勋章会把它退回待审核</b>——样式是审核过的东西，改完还算「审核通过」
     * 等于给了一条绕过审核的路（先提交一版素净的图标过审，再改成别的）。
     * 已停用的同理。纯展示性的排序不走这里，见 {@link #updateSort}，它不影响审核状态。</p>
     *
     * <p><b>审核中（待审核）的勋章一律不许改</b>，且这个限制必须写进 UPDATE 的 WHERE 里、
     * 靠影响行数判定，不能只在方法开头 if 一下：</p>
     * <ul>
     *   <li>审核人打开待审内容 → 管理员改掉它 → 审核人点通过，最终启用的是审核人<b>没看过</b>的样式；</li>
     *   <li>并发时序更直接：修改方读到待审（决定「不改状态」）→ 审核方通过置为已启用 →
     *       修改方写入新图标/新积分但不动状态，于是<b>未经审核的内容停在已启用</b>。
     *       光加方法开头的 if 拦不住这一条，因为读与写之间隔着一个审核。</li>
     * </ul>
     * 把 {@code status <> 待审核} 放进 WHERE 之后，上述两步中任何一步先完成，另一步都会
     * affected=0 而失败——这一条 UPDATE 语句本身是原子的。同理，DRAFT 期间被并发提交审核，
     * 这次修改也会落空并如实报错，而不是把内容悄悄写进别人正在审的那一版里。</p>
     *
     * @param id  勋章 id
     * @param dto 定义
     */
    public void update(Long id, MedalSaveDTO dto) {
        HonorMedal current = requireMedal(id);
        if (Integer.valueOf(MedalStatus.PENDING).equals(current.getStatus())) {
            throw new BusinessException("勋章正在样式审核中，不能修改；请等待审核结果，或先驳回再改");
        }
        HonorMedal values = new HonorMedal();
        applyDto(values, dto);

        // 用 LambdaUpdateWrapper 显式 set 而非 updateById：MP 默认跳过 null 字段，
        // 那样「手动授予要清掉旧阈值」「退回重审要清掉上一次审核痕迹」这两件事都会静默失效
        // （已被 updateEnabledMedal_fallsBackToPendingReview 抓到过一次）。
        LambdaUpdateWrapper<HonorMedal> uw = Wrappers.<HonorMedal>lambdaUpdate()
                .eq(HonorMedal::getId, id)
                // CAS：排除待审核态，把「不许改审核中的勋章」变成语句级的原子条件
                .ne(HonorMedal::getStatus, MedalStatus.PENDING)
                .set(HonorMedal::getName, values.getName())
                .set(HonorMedal::getIconUrl, values.getIconUrl())
                .set(HonorMedal::getDescription, values.getDescription())
                .set(HonorMedal::getConditionType, values.getConditionType())
                .set(HonorMedal::getConditionThreshold, values.getConditionThreshold())
                .set(HonorMedal::getRewardPoints, values.getRewardPoints())
                .set(HonorMedal::getSort, values.getSort())
                .set(HonorMedal::getUpdateTime, LocalDateTime.now());
        if (Integer.valueOf(MedalStatus.ENABLED).equals(current.getStatus())
                || Integer.valueOf(MedalStatus.DISABLED).equals(current.getStatus())) {
            uw.set(HonorMedal::getStatus, MedalStatus.PENDING)
                    .set(HonorMedal::getRejectReason, null)
                    .set(HonorMedal::getReviewBy, null)
                    .set(HonorMedal::getReviewTime, null);
        }
        if (medalMapper.update(null, uw) != 1) {
            throw new BusinessException("勋章不存在，或已进入样式审核，请刷新重试");
        }
    }

    /**
     * 只改展示排序，不触碰审核状态。
     *
     * <p><b>必须是「只 SET sort」的单条 UPDATE，不能读出整个实体再 {@code updateById} 写回</b>：
     * 后者会把读那一刻的 status / 内容 / 审核痕迹<b>整行覆盖</b>回去。并发下这等于一条绕过审核的路——
     * 另一个线程刚把已启用勋章改成待审核（{@link #update}），这边的排序保存就会把
     * {@code status=已启用} 和旧内容一起写回来，重审凭空消失。榜样那边
     * ({@code RoleModelService.updateSort}) 一直是这么写的，这里对齐。</p>
     *
     * @param id   勋章 id
     * @param sort 排序值
     */
    public void updateSort(Long id, Integer sort) {
        if (sort == null) {
            throw new BusinessException("排序值不能为空");
        }
        int rows = medalMapper.update(null, Wrappers.<HonorMedal>lambdaUpdate()
                .eq(HonorMedal::getId, id)
                .set(HonorMedal::getSort, sort)
                .set(HonorMedal::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("勋章不存在");
        }
    }

    /**
     * 删除勋章定义（逻辑删除）。
     *
     * <p>已有<b>待审或已生效</b>发放记录的勋章不允许删除：那些记录会指向一个查不到的勋章，
     * 志愿者的「我的勋章」会出现空白项。要停止使用请用 {@link #disable}。</p>
     *
     * <p><b>先对勋章行取排他锁再查发放记录</b>：这里是「查有没有在用 → 删」的读改写，
     * 而 {@link MedalGrantService#apply} 是「查是否启用 → 插发放记录」。两段交错时可以
     * 删完再插进一条指向已删勋章的记录（V28 没有外键，数据库不拦）。发起侧取共享锁、
     * 删除侧取排他锁，两者互斥，检查与动作之间不再有窗口。</p>
     *
     * @param id 勋章 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null || medalMapper.selectByIdForUpdate(id) == null) {
            throw new BusinessException("勋章不存在");
        }
        Long inUse = grantMapper.selectCount(Wrappers.<HonorMedalGrant>lambdaQuery()
                .eq(HonorMedalGrant::getMedalId, id)
                .in(HonorMedalGrant::getStatus, MedalGrantStatus.PENDING, MedalGrantStatus.EFFECTIVE));
        if (inUse != null && inUse > 0) {
            throw new BusinessException("该勋章已有发放记录，不能删除；如需停止使用请改为「停用」");
        }
        medalMapper.deleteById(id);
    }

    // ================= 样式审核 =================

    /**
     * 提交样式审核：草稿 / 已驳回 → 待审核。
     *
     * @param id 勋章 id
     */
    public void submit(Long id) {
        int rows = medalMapper.update(null, Wrappers.<HonorMedal>lambdaUpdate()
                .set(HonorMedal::getStatus, MedalStatus.PENDING)
                .set(HonorMedal::getUpdateTime, LocalDateTime.now())
                .eq(HonorMedal::getId, id)
                .in(HonorMedal::getStatus, MedalStatus.DRAFT, MedalStatus.REJECTED));
        if (rows != 1) {
            throw new BusinessException("勋章不存在，或当前状态不可提交审核");
        }
    }

    /**
     * 样式审核通过：待审核 → 已启用，<b>并把当前定义刷进「最后过审快照」</b>。
     *
     * <p>快照是志愿者端唯一的读取来源（见 {@link #approvedView}）。这一步必须与状态流转在
     * <b>同一条 UPDATE</b> 里完成，不能先置状态再补写快照：两条语句之间若失败，就会留下
     * 「状态已启用、快照还是上一版」的行，志愿者看到的仍是旧样式而管理端显示已通过。</p>
     *
     * <p>快照列直接取自同一行的当前列（{@code approved_x = x} 形式的自赋值），
     * 不用先 SELECT 再回填——那样又会引入一次「读到的值与写入时的值可能不同」的窗口，
     * 而 CAS 的 {@code status = 待审核} 只能保证状态没变，保证不了内容没变。</p>
     *
     * @param id      勋章 id
     * @param adminId 审核人
     */
    public void approve(Long id, Long adminId) {
        requireAuditor(adminId);
        LocalDateTime now = LocalDateTime.now();
        // setSql 走列自赋值：approved_name = name 等，整行原子生效
        int rows = medalMapper.update(null, Wrappers.<HonorMedal>lambdaUpdate()
                .set(HonorMedal::getStatus, MedalStatus.ENABLED)
                .set(HonorMedal::getRejectReason, null)
                .set(HonorMedal::getReviewBy, adminId)
                .set(HonorMedal::getReviewTime, now)
                .set(HonorMedal::getUpdateTime, now)
                .setSql("approved_name = name")
                .setSql("approved_icon_url = icon_url")
                .setSql("approved_description = description")
                .setSql("approved_condition_type = condition_type")
                .setSql("approved_condition_threshold = condition_threshold")
                .setSql("approved_reward_points = reward_points")
                .eq(HonorMedal::getId, id)
                .eq(HonorMedal::getStatus, MedalStatus.PENDING));
        if (rows != 1) {
            throw new BusinessException("勋章不存在或已被处理，请刷新重试");
        }
    }

    /**
     * 取「最后过审版本」的展示视图，供志愿者端使用。
     *
     * <p>返回的是一个<b>只用于展示的副本</b>，name / iconUrl / description / condition* /
     * rewardPoints 全部替换成 approved_* 快照；id、sort、status 等保持原样（sort 不参与审核，
     * status 由调用方自行判断）。</p>
     *
     * <p>快照为 null 时回退到当前值：V29 之前就存在、且从未走过 {@link #approve} 的历史行
     * （迁移已尽力回填，这里只是兜底），宁可显示当前值也不要显示一个空白勋章。</p>
     *
     * @param medal 实体
     * @return 展示副本，入参为 null 时返回 null
     */
    public HonorMedal approvedView(HonorMedal medal) {
        if (medal == null) {
            return null;
        }
        HonorMedal view = new HonorMedal();
        view.setId(medal.getId());
        view.setSort(medal.getSort());
        view.setStatus(medal.getStatus());
        /* 快照是**整体**取用还是整体回退，绝不能逐字段判空。
           反例：上一版过审时 description 为空 → approved_description 也是 NULL；
           之后管理员补了说明并送审，逐字段判空会认为「这一字段没有快照」而回退到当前值，
           于是那段**未过审**的说明立刻出现在志愿者端——正是快照要挡住的东西。
           icon_url / condition_threshold / reward_points 同理（阈值本来就允许为 NULL）。
           判据用 approved_name：name 在表上是 NOT NULL，只要写过快照它必然有值，
           因此它非空 ⟺ 这一行有过审版本。 */
        boolean hasSnapshot = medal.getApprovedName() != null;
        if (hasSnapshot) {
            view.setName(medal.getApprovedName());
            view.setIconUrl(medal.getApprovedIconUrl());
            view.setDescription(medal.getApprovedDescription());
            view.setConditionType(medal.getApprovedConditionType());
            view.setConditionThreshold(medal.getApprovedConditionThreshold());
            view.setRewardPoints(medal.getApprovedRewardPoints());
        } else {
            // V29 之前就存在、且从未走过 approve 的历史行（迁移已尽力回填，这里只是兜底）：
            // 宁可显示当前值，也不要显示一个空白勋章
            view.setName(medal.getName());
            view.setIconUrl(medal.getIconUrl());
            view.setDescription(medal.getDescription());
            view.setConditionType(medal.getConditionType());
            view.setConditionThreshold(medal.getConditionThreshold());
            view.setRewardPoints(medal.getRewardPoints());
        }
        view.setApprovedName(medal.getApprovedName());
        view.setApprovedRewardPoints(medal.getApprovedRewardPoints());
        return view;
    }

    /**
     * 样式审核驳回：待审核 → 已驳回。
     *
     * @param id      勋章 id
     * @param reason  驳回原因
     * @param adminId 审核人
     */
    public void reject(Long id, String reason, Long adminId) {
        requireAuditor(adminId);
        int rows = medalMapper.update(null, Wrappers.<HonorMedal>lambdaUpdate()
                .set(HonorMedal::getStatus, MedalStatus.REJECTED)
                .set(HonorMedal::getRejectReason, truncate(reason))
                .set(HonorMedal::getReviewBy, adminId)
                .set(HonorMedal::getReviewTime, LocalDateTime.now())
                .set(HonorMedal::getUpdateTime, LocalDateTime.now())
                .eq(HonorMedal::getId, id)
                .eq(HonorMedal::getStatus, MedalStatus.PENDING));
        if (rows != 1) {
            throw new BusinessException("勋章不存在或已被处理，请刷新重试");
        }
    }

    /**
     * 停用：已启用 → 已停用。不可再发放，<b>已生效的发放记录不受影响</b>——
     * 志愿者已经拿到的勋章不该因协会下架样式而消失。
     *
     * @param id 勋章 id
     */
    public void disable(Long id) {
        transit(id, MedalStatus.ENABLED, MedalStatus.DISABLED, "勋章不存在或不处于已启用状态");
    }

    /**
     * 重新启用：已停用 → 已启用。该样式此前已通过审核，无需再审。
     *
     * @param id 勋章 id
     */
    public void enable(Long id) {
        transit(id, MedalStatus.DISABLED, MedalStatus.ENABLED, "勋章不存在或不处于已停用状态");
    }

    // ================= 查询 =================

    /**
     * 后台勋章列表。
     *
     * @param status 状态筛选；null=全部
     * @return 按 sort、id 正序
     */
    public List<MedalVO> listForAdmin(Integer status) {
        LambdaQueryWrapper<HonorMedal> qw = Wrappers.<HonorMedal>lambdaQuery()
                .eq(status != null, HonorMedal::getStatus, status)
                .orderByAsc(HonorMedal::getSort)
                .orderByAsc(HonorMedal::getId);
        List<MedalVO> vos = new ArrayList<>();
        for (HonorMedal medal : medalMapper.selectList(qw)) {
            vos.add(toVO(medal));
        }
        return vos;
    }

    /**
     * 已启用的勋章（供志愿者端与发放选择器）。
     *
     * @return 按 sort、id 正序
     */
    public List<HonorMedal> listEnabled() {
        return medalMapper.selectList(Wrappers.<HonorMedal>lambdaQuery()
                .eq(HonorMedal::getStatus, MedalStatus.ENABLED)
                .orderByAsc(HonorMedal::getSort)
                .orderByAsc(HonorMedal::getId));
    }

    /**
     * 按 id 批量取勋章，供发放列表一次换名（避免 N+1）。
     *
     * @param ids 勋章 id 集合
     * @return 实体列表；空集合返回空列表
     */
    public List<HonorMedal> listByIds(java.util.Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : medalMapper.selectBatchIds(ids);
    }

    /**
     * 取一枚勋章，不存在则抛业务异常。
     *
     * @param id 勋章 id
     * @return 实体
     */
    public HonorMedal requireMedal(Long id) {
        HonorMedal medal = id == null ? null : medalMapper.selectById(id);
        if (medal == null) {
            throw new BusinessException("勋章不存在");
        }
        return medal;
    }

    /**
     * 取一枚勋章并加<b>共享行锁</b>（当前读），不存在则抛业务异常。<b>只能在事务内调用</b>。
     *
     * <p>供发放侧（{@link MedalGrantService#apply} / {@code approve}）确认「此刻仍是已启用」用。
     * 普通 {@link #requireMedal} 在 REPEATABLE READ 下是快照读，事务里第一条 SELECT 就把读视图
     * 定死了，之后别人把勋章停用/退回重审并提交，这里照样读到旧的「已启用」——一枚已撤下的勋章
     * 于是照样能生效、照样发分。详见 {@code HonorMedalMapper.selectByIdForShare}。</p>
     *
     * @param id 勋章 id
     * @return 实体（已持共享锁至事务提交）
     */
    public HonorMedal requireMedalForShare(Long id) {
        HonorMedal medal = id == null ? null : medalMapper.selectByIdForShare(id);
        if (medal == null) {
            throw new BusinessException("勋章不存在");
        }
        return medal;
    }

    /**
     * 实体 → 出参。
     *
     * @param medal 实体
     * @return 出参
     */
    public MedalVO toVO(HonorMedal medal) {
        MedalVO vo = new MedalVO();
        vo.setId(medal.getId());
        vo.setName(medal.getName());
        vo.setIconUrl(medal.getIconUrl());
        vo.setDescription(medal.getDescription());
        vo.setConditionType(medal.getConditionType());
        vo.setConditionTypeLabel(MedalConditionType.labelOf(medal.getConditionType()));
        vo.setConditionThreshold(medal.getConditionThreshold());
        vo.setRewardPoints(medal.getRewardPoints());
        vo.setSort(medal.getSort());
        vo.setStatus(medal.getStatus());
        vo.setStatusLabel(MedalStatus.labelOf(medal.getStatus()));
        vo.setRejectReason(medal.getRejectReason());
        vo.setReviewBy(medal.getReviewBy());
        vo.setReviewTime(medal.getReviewTime());
        vo.setCreateTime(medal.getCreateTime());
        return vo;
    }

    // ---------- helpers ----------

    private void transit(Long id, int from, int to, String message) {
        int rows = medalMapper.update(null, Wrappers.<HonorMedal>lambdaUpdate()
                .set(HonorMedal::getStatus, to)
                .set(HonorMedal::getUpdateTime, LocalDateTime.now())
                .eq(HonorMedal::getId, id)
                .eq(HonorMedal::getStatus, from));
        if (rows != 1) {
            throw new BusinessException(message);
        }
    }

    private void applyDto(HonorMedal medal, MedalSaveDTO dto) {
        int conditionType = dto.getConditionType() == null ? MedalConditionType.MANUAL : dto.getConditionType();
        if (conditionType < MedalConditionType.MANUAL || conditionType > MedalConditionType.MAX) {
            throw new BusinessException("获取条件非法");
        }
        // 有阈值的条件必须给阈值，否则「还差多少」算不出来，前端只能显示一个空进度条；
        // 手动授予则相反，带着阈值会让人以为它会自动发。两个方向都收敛掉。
        if (MedalConditionType.hasProgress(conditionType)) {
            if (dto.getConditionThreshold() == null || dto.getConditionThreshold() <= 0) {
                throw new BusinessException("该获取条件必须填写大于 0 的阈值");
            }
            medal.setConditionThreshold(dto.getConditionThreshold());
        } else {
            medal.setConditionThreshold(null);
        }
        medal.setName(dto.getName() == null ? null : dto.getName().trim());
        medal.setIconUrl(dto.getIconUrl());
        medal.setDescription(dto.getDescription());
        medal.setConditionType(conditionType);
        medal.setRewardPoints(dto.getRewardPoints() == null ? 0 : Math.max(0, dto.getRewardPoints()));
        medal.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }

    private void requireAuditor(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("审核人不能为空");
        }
    }

    private String truncate(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512);
    }
}
