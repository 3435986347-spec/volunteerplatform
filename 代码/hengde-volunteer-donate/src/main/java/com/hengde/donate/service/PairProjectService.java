package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dao.DonatePairMappers.DonatePairLetterMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairProjectMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairRecordMapper;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.entity.DonatePairLetter;
import com.hengde.donate.entity.DonatePairProject;
import com.hengde.donate.entity.DonatePairRecord;
import com.hengde.donate.vo.PairVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 助学助困结对项目与受助方来信（Row 10，V3 结对批）。
 *
 * <p><b>本批不碰支付</b>：项目上的「已认捐金额」是结对登记确认成立时累加的<b>认捐额</b>，
 * 不是已到账金额（列名 {@code pledged_amount} 就是为此取的，见 V52 文件头）。</p>
 *
 * <p><b>参加人数不存列、按登记现算</b>：存一列就有两个口径，而本项目已经在
 * {@code points_award} 与积分账本、{@code totalEarned} 与排行榜上各栽过一次。</p>
 *
 * @author hengde
 */
@Service
public class PairProjectService {

    /** 来信图片上限，与微心愿的发放图片同一量级。 */
    static final int MAX_LETTER_IMAGES = 9;

    private DonatePairProjectMapper projectMapper;
    private DonatePairRecordMapper recordMapper;
    private DonatePairLetterMapper letterMapper;

    @Autowired
    public void setProjectMapper(DonatePairProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Autowired
    public void setRecordMapper(DonatePairRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setLetterMapper(DonatePairLetterMapper letterMapper) {
        this.letterMapper = letterMapper;
    }

    // ================= 管理端：项目 =================

    /** 新建项目（落草稿，志愿者端不可见）。 */
    public Long create(PairDTOs.ProjectSave dto, Long adminId) {
        requireAdmin(adminId);
        DonatePairProject p = new DonatePairProject();
        apply(p, dto);
        p.setStatus(PairFlow.PROJECT_DRAFT);
        p.setPledgedAmount(BigDecimal.ZERO);
        p.setCreateBy(adminId);
        projectMapper.insert(p);
        return p.getId();
    }

    /**
     * 修改项目。<b>已结束的不能改</b>（条件写进 UPDATE 的 WHERE，不靠先查再改）。
     *
     * <p>进行中的项目允许改目标金额——协会核实后调整受助金额是常事；
     * 改小到低于已认捐额也放行，此时进度显示封顶 100%，不去追溯已登记的人。</p>
     */
    public void update(Long id, PairDTOs.ProjectSave dto) {
        DonatePairProject p = new DonatePairProject();
        apply(p, dto);
        int rows = projectMapper.update(null, Wrappers.<DonatePairProject>lambdaUpdate()
                .eq(DonatePairProject::getId, id)
                .in(DonatePairProject::getStatus, PairFlow.PROJECT_DRAFT, PairFlow.PROJECT_OPEN,
                        PairFlow.PROJECT_PAIRED)
                .set(DonatePairProject::getTitle, p.getTitle())
                .set(DonatePairProject::getProjectType, p.getProjectType())
                .set(DonatePairProject::getCoverUrl, p.getCoverUrl())
                .set(DonatePairProject::getDetail, p.getDetail())
                .set(DonatePairProject::getTargetAmount, p.getTargetAmount())
                .set(DonatePairProject::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("已结束的项目不能再修改（当前：" + PairFlow.projectLabel(require(id).getStatus()) + "）");
        }
    }

    /** 上架：草稿 → 进行中（志愿者端从此可见、可登记）。 */
    public void publish(Long id) {
        transit(id, PairFlow.PROJECT_DRAFT, PairFlow.PROJECT_OPEN, "只有草稿状态的项目可以上架");
    }

    /** 结束项目（进行中 / 已结对 → 已结束）。已结束的项目不再接受新的结对登记。 */
    public void end(Long id) {
        int rows = projectMapper.update(null, Wrappers.<DonatePairProject>lambdaUpdate()
                .eq(DonatePairProject::getId, id)
                .in(DonatePairProject::getStatus, PairFlow.PROJECT_OPEN, PairFlow.PROJECT_PAIRED)
                .set(DonatePairProject::getStatus, PairFlow.PROJECT_ENDED)
                .set(DonatePairProject::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("只有进行中 / 已结对的项目可以结束（当前："
                    + PairFlow.projectLabel(require(id).getStatus()) + "）");
        }
    }

    /**
     * 删除项目。<b>只有草稿可删，且不能有任何登记</b>——已经有人登记过的项目删掉，
     * 那些人的记录会指向一个不存在的项目，而他们在「我的结对」里还看得到。要下线请用「结束」。
     */
    public void delete(Long id) {
        Long registered = recordMapper.selectCount(Wrappers.<DonatePairRecord>lambdaQuery()
                .eq(DonatePairRecord::getProjectId, id));
        if (registered != null && registered > 0) {
            throw new BusinessException("这个项目已经有人登记结对，不能删除；如需下线请点「结束」");
        }
        int rows = projectMapper.delete(Wrappers.<DonatePairProject>lambdaQuery()
                .eq(DonatePairProject::getId, id)
                .eq(DonatePairProject::getStatus, PairFlow.PROJECT_DRAFT));
        if (rows != 1) {
            throw new BusinessException("只有草稿状态的项目可以删除（当前："
                    + PairFlow.projectLabel(require(id).getStatus()) + "）");
        }
    }

    /** 后台列表（状态 / 类型 / 关键词）。 */
    public PageResult<PairVOs.Project> listForAdmin(PageQuery query, Integer status, Integer projectType,
                                                    String keyword) {
        IPage<DonatePairProject> page = projectMapper.selectPage(query.toPage(),
                Wrappers.<DonatePairProject>lambdaQuery()
                        .eq(status != null, DonatePairProject::getStatus, status)
                        .eq(projectType != null, DonatePairProject::getProjectType, projectType)
                        .like(StringUtils.hasText(keyword), DonatePairProject::getTitle,
                                keyword == null ? null : keyword.trim())
                        .orderByDesc(DonatePairProject::getId));
        return toPage(page, null, false);
    }

    public PairVOs.Project detailForAdmin(Long id) {
        DonatePairProject p = require(id);
        PairVOs.Project vo = toVO(p, participantCounts(List.of(p.getId())).get(p.getId()));
        vo.setDetail(p.getDetail());
        return vo;
    }

    // ================= 志愿者端：项目 =================

    /**
     * 项目列表（Row 10 的四个页签）：{@code tab} 1结对助学 / 2结对助困 / 3结对助残 / 4结对成功。
     *
     * <p>前三个页签只列<b>进行中</b>的项目（那是「还能结对」的意思）；第四个页签列<b>已结对</b>的，
     * 不分类型——「结对成功」是一种状态而不是一种类型。草稿与已结束的项目志愿者端一律不可见。</p>
     */
    public PageResult<PairVOs.Project> listForVolunteer(PageQuery query, Integer tab, Long volunteerId) {
        LambdaQueryWrapper<DonatePairProject> q = Wrappers.<DonatePairProject>lambdaQuery();
        if (tab != null && tab == 4) {
            q.eq(DonatePairProject::getStatus, PairFlow.PROJECT_PAIRED);
        } else {
            q.eq(DonatePairProject::getStatus, PairFlow.PROJECT_OPEN);
            if (tab != null && tab != 0) {
                if (!PairFlow.isValidType(tab)) {
                    throw new BusinessException("tab 只能是 1助学 / 2助困 / 3助残 / 4结对成功");
                }
                q.eq(DonatePairProject::getProjectType, tab);
            }
        }
        IPage<DonatePairProject> page = projectMapper.selectPage(query.toPage(), q.orderByDesc(DonatePairProject::getId));
        return toPage(page, volunteerId, true);
    }

    /** 项目详情（含我的登记与「此刻能不能登记」）。草稿对志愿者等于不存在。 */
    public PairVOs.Project detailForVolunteer(Long id, Long volunteerId) {
        DonatePairProject p = id == null ? null : projectMapper.selectById(id);
        if (p == null || Objects.equals(p.getStatus(), PairFlow.PROJECT_DRAFT)) {
            throw new BusinessException("项目不存在");
        }
        PairVOs.Project vo = toVO(p, participantCounts(List.of(p.getId())).get(p.getId()));
        vo.setDetail(p.getDetail());
        attachMyPair(List.of(vo), volunteerId);
        return vo;
    }

    // ================= 受助方来信 =================

    /** 录入一封来信（Row 10：受助方不直接使用系统，由协会代录）。 */
    public Long addLetter(Long projectId, PairDTOs.LetterSave dto, Long adminId) {
        requireAdmin(adminId);
        require(projectId);
        if (dto == null || !StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("请填写来信内容");
        }
        if (dto.getPairRecordId() != null) {
            DonatePairRecord r = recordMapper.selectById(dto.getPairRecordId());
            if (r == null || !projectId.equals(r.getProjectId())) {
                throw new BusinessException("收信的结对登记不属于这个项目");
            }
        }
        DonatePairLetter l = new DonatePairLetter();
        l.setProjectId(projectId);
        l.setPairRecordId(dto.getPairRecordId());
        l.setTitle(trimToNull(dto.getTitle()));
        l.setContent(dto.getContent().trim());
        l.setImageUrls(joinImages(dto.getImages()));
        l.setWriteTime(dto.getWriteTime() == null ? LocalDateTime.now() : dto.getWriteTime());
        l.setCreateBy(adminId);
        letterMapper.insert(l);
        return l.getId();
    }

    /** 删除来信（逻辑删除）。 */
    public void deleteLetter(Long letterId) {
        if (letterMapper.deleteById(letterId) != 1) {
            throw new BusinessException("来信不存在");
        }
    }

    /**
     * 志愿者看到的来信：<b>项目公开信 + 写给我的那些</b>。
     *
     * <p>过滤落在服务端而不是前端：写给某一位结对人的信里往往有受助方的具体情况，
     * 不是给所有人看的。</p>
     */
    public PageResult<PairVOs.Letter> lettersForVolunteer(Long projectId, Long volunteerId, PageQuery query) {
        List<Long> myPairIds = recordMapper.selectList(Wrappers.<DonatePairRecord>lambdaQuery()
                        .select(DonatePairRecord::getId)
                        .eq(DonatePairRecord::getProjectId, projectId)
                        .eq(DonatePairRecord::getVolunteerId, volunteerId))
                .stream().map(DonatePairRecord::getId).toList();
        IPage<DonatePairLetter> page = letterMapper.selectPage(query.toPage(),
                Wrappers.<DonatePairLetter>lambdaQuery()
                        .eq(DonatePairLetter::getProjectId, projectId)
                        .and(w -> {
                            w.isNull(DonatePairLetter::getPairRecordId);
                            if (!myPairIds.isEmpty()) {
                                w.or().in(DonatePairLetter::getPairRecordId, myPairIds);
                            }
                        })
                        .orderByDesc(DonatePairLetter::getId));
        return PageResult.of(page.convert(l -> toLetterVO(l, !myPairIds.isEmpty()
                && myPairIds.contains(l.getPairRecordId()), false)));
    }

    /** 后台看全部来信（含写给某个人的）。 */
    public PageResult<PairVOs.Letter> lettersForAdmin(Long projectId, PageQuery query) {
        IPage<DonatePairLetter> page = letterMapper.selectPage(query.toPage(),
                Wrappers.<DonatePairLetter>lambdaQuery()
                        .eq(projectId != null, DonatePairLetter::getProjectId, projectId)
                        .orderByDesc(DonatePairLetter::getId));
        return PageResult.of(page.convert(l -> toLetterVO(l, false, true)));
    }

    // ================= 给同域其它服务用 =================

    public DonatePairProject require(Long id) {
        DonatePairProject p = id == null ? null : projectMapper.selectById(id);
        if (p == null) {
            throw new BusinessException("项目不存在");
        }
        return p;
    }

    /** 登记结对时用：项目必须在「进行中」。 */
    public DonatePairProject requireOpen(Long id) {
        DonatePairProject p = require(id);
        if (!Objects.equals(p.getStatus(), PairFlow.PROJECT_OPEN)) {
            throw new BusinessException("这个项目当前不接受结对登记（" + PairFlow.projectLabel(p.getStatus()) + "）");
        }
        return p;
    }

    /**
     * 认捐额达标就把项目置「已结对」（Row 10 的「结对成功」页签）。
     *
     * <p>条件全部写进 UPDATE：只在「进行中且认捐额已达目标」时生效，
     * 并发确认两笔结对时，后提交的那一次自然会看到达标并置位，前一次影响 0 行、什么也不做。</p>
     */
    public void markPairedIfFull(Long projectId) {
        projectMapper.update(null, Wrappers.<DonatePairProject>lambdaUpdate()
                .eq(DonatePairProject::getId, projectId)
                .eq(DonatePairProject::getStatus, PairFlow.PROJECT_OPEN)
                .apply("pledged_amount >= target_amount")
                .set(DonatePairProject::getStatus, PairFlow.PROJECT_PAIRED)
                .set(DonatePairProject::getUpdateTime, LocalDateTime.now()));
    }

    /** 取消已成立的结对、认捐额退回到目标以下时，项目回到「进行中」继续招募。 */
    public void reopenIfNotFull(Long projectId) {
        projectMapper.update(null, Wrappers.<DonatePairProject>lambdaUpdate()
                .eq(DonatePairProject::getId, projectId)
                .eq(DonatePairProject::getStatus, PairFlow.PROJECT_PAIRED)
                .apply("pledged_amount < target_amount")
                .set(DonatePairProject::getStatus, PairFlow.PROJECT_OPEN)
                .set(DonatePairProject::getUpdateTime, LocalDateTime.now()));
    }

    /** 批量取项目名（给结对登记列表与证书展示用）。 */
    public Map<Long, DonatePairProject> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectList(Wrappers.<DonatePairProject>lambdaQuery()
                        .in(DonatePairProject::getId, ids))
                .stream().collect(Collectors.toMap(DonatePairProject::getId, p -> p, (a, b) -> a));
    }

    // ================= 内部 =================

    private void transit(Long id, int from, int to, String rejectMessage) {
        int rows = projectMapper.update(null, Wrappers.<DonatePairProject>lambdaUpdate()
                .eq(DonatePairProject::getId, id)
                .eq(DonatePairProject::getStatus, from)
                .set(DonatePairProject::getStatus, to)
                .set(DonatePairProject::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException(rejectMessage + "（当前：" + PairFlow.projectLabel(require(id).getStatus()) + "）");
        }
    }

    private void apply(DonatePairProject p, PairDTOs.ProjectSave dto) {
        if (dto == null || !StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException("请填写项目名称");
        }
        int type = dto.getProjectType() == null ? PairFlow.TYPE_STUDY : dto.getProjectType();
        if (!PairFlow.isValidType(type)) {
            throw new BusinessException("项目类型只能是 1结对助学 / 2结对助困 / 3结对助残");
        }
        if (dto.getTargetAmount() == null || dto.getTargetAmount().signum() <= 0) {
            throw new BusinessException("受助金额须大于 0");
        }
        p.setTitle(dto.getTitle().trim());
        p.setProjectType(type);
        p.setCoverUrl(trimToNull(dto.getCoverUrl()));
        p.setDetail(trimToNull(dto.getDetail()));
        p.setTargetAmount(dto.getTargetAmount());
    }

    private PageResult<PairVOs.Project> toPage(IPage<DonatePairProject> page, Long volunteerId, boolean withMyPair) {
        Map<Long, Integer> counts = participantCounts(page.getRecords().stream()
                .map(DonatePairProject::getId).toList());
        PageResult<PairVOs.Project> result = PageResult.of(page.convert(p -> toVO(p, counts.get(p.getId()))));
        if (withMyPair) {
            attachMyPair(result.getRecords(), volunteerId);
        }
        return result;
    }

    /** 我的登记一次批量查，不按项目循环。 */
    private void attachMyPair(List<PairVOs.Project> projects, Long volunteerId) {
        if (volunteerId == null || projects.isEmpty()) {
            return;
        }
        List<Long> ids = projects.stream().map(PairVOs.Project::getId).toList();
        Map<Long, DonatePairRecord> mine = recordMapper.selectList(Wrappers.<DonatePairRecord>lambdaQuery()
                        .eq(DonatePairRecord::getVolunteerId, volunteerId)
                        .in(DonatePairRecord::getProjectId, ids)
                        .in(DonatePairRecord::getStatus, PairFlow.PAIR_REGISTERED, PairFlow.PAIR_ESTABLISHED))
                .stream().collect(Collectors.toMap(DonatePairRecord::getProjectId, r -> r, (a, b) -> a));
        for (PairVOs.Project vo : projects) {
            DonatePairRecord r = mine.get(vo.getId());
            if (r != null) {
                vo.setMyPair(toRecordVO(r, vo.getTitle()));
            }
            // 「能不能登记」＝项目在进行中，且我还没有一条活着的登记
            vo.setCanRegister(Objects.equals(vo.getStatus(), PairFlow.PROJECT_OPEN) && r == null);
        }
    }

    private Map<Long, Integer> participantCounts(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> out = new HashMap<>();
        for (Map<String, Object> row : projectMapper.countParticipants(projectIds,
                PairFlow.PAIR_REGISTERED, PairFlow.PAIR_ESTABLISHED)) {
            Object pid = row.get("projectId");
            Object cnt = row.get("cnt");
            if (pid != null && cnt != null) {
                out.put(((Number) pid).longValue(), ((Number) cnt).intValue());
            }
        }
        return out;
    }

    static PairVOs.Project toVO(DonatePairProject p, Integer participants) {
        PairVOs.Project vo = new PairVOs.Project();
        vo.setId(p.getId());
        vo.setTitle(p.getTitle());
        vo.setProjectType(p.getProjectType());
        vo.setProjectTypeLabel(PairFlow.typeLabel(p.getProjectType()));
        vo.setCoverUrl(p.getCoverUrl());
        vo.setTargetAmount(p.getTargetAmount());
        vo.setPledgedAmount(p.getPledgedAmount());
        vo.setRaisedAmount(p.getRaisedAmount());
        vo.setProgressPercent(percent(p.getPledgedAmount(), p.getTargetAmount()));
        vo.setParticipantCount(participants == null ? 0 : participants);
        vo.setStatus(p.getStatus());
        vo.setStatusLabel(PairFlow.projectLabel(p.getStatus()));
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }

    static PairVOs.PairRecord toRecordVO(DonatePairRecord r, String projectTitle) {
        PairVOs.PairRecord vo = new PairVOs.PairRecord();
        vo.setId(r.getId());
        vo.setProjectId(r.getProjectId());
        vo.setProjectTitle(projectTitle);
        vo.setAmount(r.getAmount());
        vo.setPaidAmount(r.getPaidAmount());
        vo.setAmountType(r.getAmountType());
        vo.setStatus(r.getStatus());
        vo.setStatusLabel(PairFlow.pairLabel(r.getStatus()));
        vo.setRegisterTime(r.getRegisterTime());
        vo.setEstablishedTime(r.getEstablishedTime());
        vo.setCancelTime(r.getCancelTime());
        vo.setCancelReason(r.getCancelReason());
        vo.setRemark(r.getRemark());
        return vo;
    }

    private static PairVOs.Letter toLetterVO(DonatePairLetter l, boolean forMe, boolean forAdmin) {
        PairVOs.Letter vo = new PairVOs.Letter();
        vo.setId(l.getId());
        vo.setProjectId(l.getProjectId());
        vo.setTitle(l.getTitle());
        vo.setContent(l.getContent());
        if (StringUtils.hasText(l.getImageUrls())) {
            vo.setImages(new ArrayList<>(Arrays.asList(l.getImageUrls().split("\n"))));
        }
        vo.setWriteTime(l.getWriteTime());
        vo.setForMe(forMe);
        vo.setCreateTime(l.getCreateTime());
        if (forAdmin) {
            vo.setPairRecordId(l.getPairRecordId());
        }
        return vo;
    }

    /** 进度百分比，封顶 100（目标为 0 时按 0 处理，避免除零）。 */
    static Integer percent(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() <= 0) {
            return 0;
        }
        int p = part.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.DOWN).intValue();
        return Math.min(100, Math.max(0, p));
    }

    private static String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        if (images.size() > MAX_LETTER_IMAGES) {
            throw new BusinessException("图片最多 " + MAX_LETTER_IMAGES + " 张");
        }
        List<String> cleaned = new ArrayList<>();
        for (String s : images) {
            String v = trimToNull(s);
            if (v == null) {
                throw new BusinessException("图片地址不能为空");
            }
            // 换行是存储分隔符：地址里混进换行，读出来就会被拆成两张图
            if (v.length() > 512 || v.contains("\n") || v.contains("\r")) {
                throw new BusinessException("图片地址不正确");
            }
            cleaned.add(v);
        }
        return String.join("\n", cleaned);
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
