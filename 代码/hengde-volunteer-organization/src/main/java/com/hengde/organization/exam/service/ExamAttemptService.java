package com.hengde.organization.exam.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.exam.dao.OrgExamAttemptMapper;
import com.hengde.organization.exam.dao.OrgExamPaperMapper;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.entity.OrgExamAttempt;
import com.hengde.organization.exam.entity.OrgExamPaper;
import com.hengde.organization.exam.entity.OrgTempLeaderQualification;
import com.hengde.organization.exam.support.ExamCodes;
import com.hengde.organization.exam.support.ExamScoring;
import com.hengde.organization.exam.vo.ExamVOs;
import com.hengde.organization.form.support.FormAnswerValidator;
import com.hengde.organization.form.support.QuestionType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 临时负责人考试的交卷、阅卷与历史（V4 临时负责人考试批，xlsx Row 14 / Row 45）。
 *
 * <p><b>判分</b>：单选 / 多选 / 判断交卷即自动判（多选全对才得分，Q33）；填空 / 简答由阅卷人逐题给分（Row 45 F「主观题需要人工审核」）。
 * 没有主观题的试卷交卷即出分；有主观题的落「待阅卷」，<b>一个人至多一份待阅卷</b>（{@code uk_active_pending}），出分后才能再考。
 * <b>总分达到及格线即获得资格</b>（与出分同一事务），不及格可以再考，次数不限（Q34）。</p>
 *
 * <p><b>串行化</b>：交卷、阅卷、撤销资格都持同一把按人的 Redisson 锁（{@link ExamCodes#LOCK_PREFIX}，锁在事务外）——
 * 交卷前「已经是临时负责人了吗 / 有没有待阅卷的」两条判断与阅卷授予资格之间没有窗口，
 * 否则阅卷刚授予资格、同一个人又交进一份新卷，会拿到第二份「待阅卷」。
 * 交卷事务的第一条语句是试卷行的当前读共享锁（{@link OrgExamPaperMapper#selectByIdForShare}），与「停止」串行。</p>
 *
 * <p><b>志愿者看不到标准答案与逐题得分</b>（同一份试卷可以重考，Q34）：历史里只有自己的作答与总分。</p>
 *
 * @author hengde
 */
@Service
public class ExamAttemptService {

    private OrgExamAttemptMapper attemptMapper;
    private OrgExamPaperMapper paperMapper;
    private ExamPaperService paperService;
    private TempLeaderService tempLeaderService;
    private TempLeaderQueryService tempLeaderQueryService;
    private VolunteerQueryService volunteerQueryService;
    private AdminQueryService adminQueryService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setAttemptMapper(OrgExamAttemptMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    @Autowired
    public void setPaperMapper(OrgExamPaperMapper paperMapper) {
        this.paperMapper = paperMapper;
    }

    @Autowired
    public void setPaperService(ExamPaperService paperService) {
        this.paperService = paperService;
    }

    @Autowired
    public void setTempLeaderService(TempLeaderService tempLeaderService) {
        this.tempLeaderService = tempLeaderService;
    }

    @Autowired
    public void setTempLeaderQueryService(TempLeaderQueryService tempLeaderQueryService) {
        this.tempLeaderQueryService = tempLeaderQueryService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 志愿者端 =================

    /** 我的考试：当前开放的试卷（能考时才带题目，不含答案）+ 我的资格 + 能不能考。 */
    public ExamVOs.MyExam myExam(Long volunteerId) {
        ExamVOs.MyExam vo = new ExamVOs.MyExam();
        OrgTempLeaderQualification q = tempLeaderQueryService.current(volunteerId);
        vo.setTempLeader(q != null);
        vo.setQualificationExpireTime(q == null ? null : q.getExpireTime());
        boolean pending = hasPending(volunteerId);
        vo.setHasPendingAttempt(pending);
        OrgExamPaper paper = paperService.openPaper();
        String reason = eligibilityProblem(volunteerId);
        if (reason == null && q != null) {
            reason = alreadyQualified(q);
        }
        if (reason == null && pending) {
            reason = "你有一份答卷正在阅卷，出分后才能再考";
        }
        if (reason == null && paper == null) {
            reason = "目前没有开放的考试";
        }
        vo.setCanTake(reason == null);
        vo.setReason(reason);
        if (paper != null) {
            ExamVOs.Paper p = ExamPaperService.toVO(paper);
            List<ExamScoring.Item> items = paperService.items(paper.getId());
            p.setQuestionCount(items.size());
            if (reason == null) {
                p.setQuestions(items.stream().map(i -> ExamPaperService.questionVO(i, false)).toList());
            }
            vo.setPaper(p);
        }
        return vo;
    }

    /** 交卷。没有主观题的当场出分，及格即获得资格。 */
    public ExamVOs.Attempt submit(Long volunteerId, ExamDTOs.Submit dto) {
        if (volunteerId == null) {
            throw new BusinessException("请先登录");
        }
        if (dto == null || dto.getPaperId() == null) {
            throw new BusinessException("缺少试卷 id");
        }
        String problem = eligibilityProblem(volunteerId);
        if (problem != null) {
            throw new BusinessException(problem);
        }
        Long attemptId = DistributedLockSupport.runLocked(redissonClient, ExamCodes.LOCK_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doSubmit(volunteerId, dto)));
        return toVO(attemptMapper.selectById(attemptId), null, null);
    }

    private Long doSubmit(Long volunteerId, ExamDTOs.Submit dto) {
        OrgExamPaper paper = paperMapper.selectByIdForShare(dto.getPaperId());
        if (paper == null) {
            throw new BusinessException("试卷不存在");
        }
        if (!Objects.equals(paper.getStatus(), ExamCodes.PAPER_OPEN)) {
            throw new BusinessException("这份试卷已经停止考试，请刷新");
        }
        OrgTempLeaderQualification q = tempLeaderQueryService.current(volunteerId);
        if (q != null) {
            throw new BusinessException(alreadyQualified(q));
        }
        if (hasPending(volunteerId)) {
            throw new BusinessException("你有一份答卷正在阅卷，出分后才能再考");
        }
        List<ExamScoring.Item> items = paperService.items(paper.getId());
        List<FormAnswerValidator.Normalized> answers;
        try {
            answers = FormAnswerValidator.validate(items.stream().map(ExamScoring.Item::definition).toList(),
                    dto.getAnswers(), url -> false);
        } catch (BusinessException e) {
            throw new BusinessException(e.getMessage().replace("问卷", "试卷"));
        }
        Map<Long, Object> byQuestion = new HashMap<>();
        for (FormAnswerValidator.Normalized n : answers) {
            byQuestion.put(n.questionId(), n.value());
        }
        int objective = 0;
        boolean hasSubjective = false;
        for (ExamScoring.Item i : items) {
            if (i.subjective()) {
                hasSubjective = true;
            } else {
                objective += ExamScoring.objectiveScore(i, byQuestion.get(i.definition().id()));
            }
        }
        LocalDateTime now = LocalDateTime.now();
        OrgExamAttempt a = new OrgExamAttempt();
        a.setPaperId(paper.getId());
        a.setVolunteerId(volunteerId);
        a.setAnswersJson(FormAnswerValidator.toJson(answers));
        a.setObjectiveScore(objective);
        a.setSubmitTime(now);
        boolean passed = false;
        if (hasSubjective) {
            a.setStatus(ExamCodes.ATTEMPT_PENDING);
        } else {
            passed = objective >= paper.getPassScore();
            a.setStatus(ExamCodes.ATTEMPT_GRADED);
            a.setSubjectiveScore(0);
            a.setTotalScore(objective);
            a.setPassed(passed ? 1 : 0);
            a.setGradedTime(now);
        }
        try {
            attemptMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("你有一份答卷正在阅卷，出分后才能再考");
        }
        if (passed) {
            tempLeaderService.grantInTx(volunteerId, a.getId(), paper.getQualificationMonths(), now);
        }
        return a.getId();
    }

    /** 我的考试历史（新的在前）。 */
    public PageResult<ExamVOs.Attempt> myAttempts(Long volunteerId, PageQuery query) {
        IPage<OrgExamAttempt> page = attemptMapper.selectPage(query.toPage(), Wrappers.<OrgExamAttempt>lambdaQuery()
                .eq(OrgExamAttempt::getVolunteerId, volunteerId)
                .orderByDesc(OrgExamAttempt::getId));
        return PageResult.of(toVOs(page.getRecords(), false), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 我的一份答卷：自己的作答与总分，<b>不含标准答案与逐题得分</b>。不是本人的与不存在的同一句话。 */
    public ExamVOs.Attempt myAttemptDetail(Long volunteerId, Long attemptId) {
        OrgExamAttempt a = attemptId == null ? null : attemptMapper.selectById(attemptId);
        if (a == null || !Objects.equals(a.getVolunteerId(), volunteerId)) {
            throw new BusinessException("答卷不存在");
        }
        return detail(a, false);
    }

    // ================= 管理端 =================

    /** @param status 1待阅卷 / 2已出分；空＝全部 */
    public PageResult<ExamVOs.Attempt> list(PageQuery query, Integer status, Long paperId, Long volunteerId, String keyword) {
        LambdaQueryWrapper<OrgExamAttempt> w = Wrappers.<OrgExamAttempt>lambdaQuery()
                .eq(status != null, OrgExamAttempt::getStatus, status)
                .eq(paperId != null, OrgExamAttempt::getPaperId, paperId)
                .eq(volunteerId != null, OrgExamAttempt::getVolunteerId, volunteerId);
        if (StringUtils.hasText(keyword)) {
            List<Long> ids = volunteerQueryService.findIdsByNameOrPhone(keyword.trim(), 500);
            if (ids.isEmpty()) {
                return PageResult.of(List.of(), 0, query.getPage(), query.getSize());
            }
            w.in(OrgExamAttempt::getVolunteerId, ids);
        }
        // 待阅卷先交的先阅
        w.orderBy(true, Objects.equals(status, ExamCodes.ATTEMPT_PENDING), OrgExamAttempt::getId);
        IPage<OrgExamAttempt> page = attemptMapper.selectPage(query.toPage(), w);
        return PageResult.of(toVOs(page.getRecords(), true), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 管理端答卷详情：逐题作答、逐题得分、标准答案 / 参考答案。 */
    public ExamVOs.Attempt adminDetail(Long attemptId) {
        OrgExamAttempt a = attemptId == null ? null : attemptMapper.selectById(attemptId);
        if (a == null) {
            throw new BusinessException("答卷不存在");
        }
        return detail(a, true);
    }

    /** 阅卷：给每一道主观题打分，出分；及格即获得资格（同一事务）。 */
    public ExamVOs.Attempt grade(Long attemptId, ExamDTOs.Grade dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgExamAttempt a = attemptId == null ? null : attemptMapper.selectById(attemptId);
        if (a == null) {
            throw new BusinessException("答卷不存在");
        }
        String note = dto == null || !StringUtils.hasText(dto.getNote()) ? null : dto.getNote().trim();
        if (note != null && note.length() > 255) {
            throw new BusinessException("阅卷备注不超过 255 字");
        }
        DistributedLockSupport.runLocked(redissonClient, ExamCodes.LOCK_PREFIX + a.getVolunteerId(),
                () -> transactionTemplate.execute(s -> {
                    doGrade(attemptId, dto, note, adminId);
                    return null;
                }));
        return adminDetail(attemptId);
    }

    private void doGrade(Long attemptId, ExamDTOs.Grade dto, String note, Long adminId) {
        OrgExamAttempt a = attemptMapper.selectById(attemptId);
        if (a == null || !Objects.equals(a.getStatus(), ExamCodes.ATTEMPT_PENDING)) {
            throw new BusinessException("这份答卷已经出分了，请刷新");
        }
        OrgExamPaper paper = paperService.require(a.getPaperId());
        Map<Long, ExamScoring.Item> subjective = new LinkedHashMap<>();
        for (ExamScoring.Item i : paperService.items(a.getPaperId())) {
            if (i.subjective()) {
                subjective.put(i.definition().id(), i);
            }
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        int sum = 0;
        if (dto != null && dto.getScores() != null) {
            for (ExamDTOs.QuestionScore s : dto.getScores()) {
                if (s == null || s.getQuestionId() == null || !subjective.containsKey(s.getQuestionId())) {
                    throw new BusinessException("只能给这份试卷的主观题（填空 / 简答）打分");
                }
                if (!seen.add(s.getQuestionId())) {
                    throw new BusinessException("同一道题打了两次分");
                }
                ExamScoring.Item item = subjective.get(s.getQuestionId());
                if (s.getScore() == null || s.getScore() < 0 || s.getScore() > item.score()) {
                    throw new BusinessException("第 " + item.definition().sort() + " 题的得分要在 0–" + item.score() + " 之间");
                }
                scores.put(String.valueOf(s.getQuestionId()), s.getScore());
                sum += s.getScore();
            }
        }
        if (seen.size() != subjective.size()) {
            ExamScoring.Item missing = subjective.values().stream()
                    .filter(i -> !seen.contains(i.definition().id())).findFirst().orElseThrow();
            throw new BusinessException("第 " + missing.definition().sort() + " 题还没打分");
        }
        int total = a.getObjectiveScore() + sum;
        boolean passed = total >= paper.getPassScore();
        LocalDateTime now = LocalDateTime.now();
        int rows = attemptMapper.update(null, Wrappers.<OrgExamAttempt>lambdaUpdate()
                .eq(OrgExamAttempt::getId, attemptId)
                .eq(OrgExamAttempt::getStatus, ExamCodes.ATTEMPT_PENDING)
                .set(OrgExamAttempt::getStatus, ExamCodes.ATTEMPT_GRADED)
                .set(OrgExamAttempt::getSubjectiveJson, JSONUtil.toJsonStr(scores))
                .set(OrgExamAttempt::getSubjectiveScore, sum)
                .set(OrgExamAttempt::getTotalScore, total)
                .set(OrgExamAttempt::getPassed, passed ? 1 : 0)
                .set(OrgExamAttempt::getGradeNote, note)
                .set(OrgExamAttempt::getGradedBy, adminId)
                .set(OrgExamAttempt::getGradedTime, now));
        if (rows != 1) {
            throw new BusinessException("这份答卷已经出分了，请刷新");
        }
        if (passed) {
            tempLeaderService.grantInTx(a.getVolunteerId(), attemptId, paper.getQualificationMonths(), now);
        }
    }

    // ================= 内部 =================

    /** 账号与实名；没问题返回 null。 */
    private String eligibilityProblem(Long volunteerId) {
        if (!volunteerQueryService.isActive(volunteerId)) {
            return "账号状态异常，无法参加考试";
        }
        VolunteerFlagInfoView info = volunteerQueryService.getFlagInfo(volunteerId);
        if (info == null || !info.registered()) {
            return "请先完成实名注册再参加考试";
        }
        return null;
    }

    private static String alreadyQualified(OrgTempLeaderQualification q) {
        return "你已经是活动临时负责人了（" + (q.getExpireTime() == null ? "长期有效"
                : "有效期至 " + q.getExpireTime().toLocalDate()) + "）";
    }

    private boolean hasPending(Long volunteerId) {
        Long n = attemptMapper.selectCount(Wrappers.<OrgExamAttempt>lambdaQuery()
                .eq(OrgExamAttempt::getVolunteerId, volunteerId)
                .eq(OrgExamAttempt::getStatus, ExamCodes.ATTEMPT_PENDING));
        return n != null && n > 0;
    }

    private ExamVOs.Attempt detail(OrgExamAttempt a, boolean admin) {
        OrgExamPaper paper = paperService.require(a.getPaperId());
        String name = admin ? volunteerQueryService.listNamesByIds(List.of(a.getVolunteerId())).get(a.getVolunteerId()) : null;
        String grader = admin && a.getGradedBy() != null
                ? adminQueryService.listNamesByIds(List.of(a.getGradedBy())).get(a.getGradedBy()) : null;
        ExamVOs.Attempt vo = toVO(a, paper, name);
        vo.setGradedByName(grader);
        Map<Long, Object> answers = FormAnswerValidator.fromJson(a.getAnswersJson());
        JSONObject subjective = StringUtils.hasText(a.getSubjectiveJson()) ? JSONUtil.parseObj(a.getSubjectiveJson()) : new JSONObject();
        List<ExamVOs.AnswerRow> rows = new ArrayList<>();
        for (ExamScoring.Item i : paperService.items(a.getPaperId())) {
            FormAnswerValidator.Definition d = i.definition();
            Object v = answers.get(d.id());
            ExamVOs.AnswerRow r = new ExamVOs.AnswerRow();
            r.setQuestionId(d.id());
            r.setSort(d.sort());
            r.setType(d.type());
            r.setTypeLabel(QuestionType.label(d.type()));
            r.setTitle(d.title());
            r.setFullScore(i.score());
            r.setSubjective(i.subjective());
            r.setValue(v);
            r.setDisplay(FormAnswerValidator.display(d, v));
            if (admin) {
                r.setScore(i.subjective() ? subjective.getInt(String.valueOf(d.id())) : ExamScoring.objectiveScore(i, v));
                r.setAnswerDisplay(i.answer() == null ? null : FormAnswerValidator.display(d, i.answer()));
            }
            rows.add(r);
        }
        vo.setAnswers(rows);
        return vo;
    }

    private List<ExamVOs.Attempt> toVOs(List<OrgExamAttempt> rows, boolean admin) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, OrgExamPaper> papers = new HashMap<>();
        for (OrgExamPaper p : paperMapper.selectBatchIds(rows.stream().map(OrgExamAttempt::getPaperId).distinct().toList())) {
            papers.put(p.getId(), p);
        }
        Map<Long, String> names = admin
                ? volunteerQueryService.listNamesByIds(rows.stream().map(OrgExamAttempt::getVolunteerId).distinct().toList())
                : Map.of();
        List<Long> graders = rows.stream().map(OrgExamAttempt::getGradedBy).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> graderNames = admin && !graders.isEmpty() ? adminQueryService.listNamesByIds(graders) : Map.of();
        List<ExamVOs.Attempt> out = new ArrayList<>(rows.size());
        for (OrgExamAttempt a : rows) {
            ExamVOs.Attempt vo = toVO(a, papers.get(a.getPaperId()), names.get(a.getVolunteerId()));
            vo.setGradedByName(a.getGradedBy() == null ? null : graderNames.get(a.getGradedBy()));
            out.add(vo);
        }
        return out;
    }

    private ExamVOs.Attempt toVO(OrgExamAttempt a, OrgExamPaper paper, String volunteerName) {
        if (paper == null) {
            paper = paperMapper.selectById(a.getPaperId());
        }
        ExamVOs.Attempt vo = new ExamVOs.Attempt();
        vo.setId(a.getId());
        vo.setPaperId(a.getPaperId());
        if (paper != null) {
            vo.setPaperTitle(paper.getTitle());
            vo.setPassScore(paper.getPassScore());
            vo.setPaperTotalScore(paper.getTotalScore());
        }
        vo.setVolunteerId(a.getVolunteerId());
        vo.setVolunteerName(volunteerName);
        vo.setObjectiveScore(a.getObjectiveScore());
        vo.setSubjectiveScore(a.getSubjectiveScore());
        vo.setTotalScore(a.getTotalScore());
        vo.setPassed(a.getPassed() == null ? null : a.getPassed() == 1);
        vo.setStatus(a.getStatus());
        vo.setStatusLabel(ExamCodes.attemptStatusLabel(a.getStatus()));
        vo.setGradeNote(a.getGradeNote());
        vo.setGradedTime(a.getGradedTime());
        vo.setSubmitTime(a.getSubmitTime());
        return vo;
    }
}
