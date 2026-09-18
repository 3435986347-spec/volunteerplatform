package com.hengde.organization.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.exam.dao.OrgExamAttemptMapper;
import com.hengde.organization.exam.dao.OrgExamPaperMapper;
import com.hengde.organization.exam.dao.OrgExamQuestionMapper;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.entity.OrgExamPaper;
import com.hengde.organization.exam.entity.OrgExamQuestion;
import com.hengde.organization.exam.support.ExamCodes;
import com.hengde.organization.exam.support.ExamScoring;
import com.hengde.organization.exam.vo.ExamVOs;
import com.hengde.organization.form.support.FormAnswerValidator;
import com.hengde.organization.form.support.QuestionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 临时负责人考试试卷（V4 临时负责人考试批，管理端 {@code org:exam}）：建 / 改草稿 / 开放 / 停止 / 复制 / 删草稿。
 *
 * <p><b>题目、分值、及格线只在草稿时能改</b>（条件写在 UPDATE 的 WHERE 里）：开放之后有人按这套题交了卷，
 * 改分值或及格线会让已出的分数跟着变味，要改就复制出一份新草稿。</p>
 *
 * <p><b>同一时刻至多一份开放中的试卷</b>（{@code uk_active_open}），开放撞键即报「请先停止它」。停止后已交的答卷照常阅卷。</p>
 *
 * @author hengde
 */
@Service
public class ExamPaperService {

    private OrgExamPaperMapper paperMapper;
    private OrgExamQuestionMapper questionMapper;
    private OrgExamAttemptMapper attemptMapper;

    @Autowired
    public void setPaperMapper(OrgExamPaperMapper paperMapper) {
        this.paperMapper = paperMapper;
    }

    @Autowired
    public void setQuestionMapper(OrgExamQuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    @Autowired
    public void setAttemptMapper(OrgExamAttemptMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(ExamDTOs.PaperSave dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgExamPaper p = new OrgExamPaper();
        List<ExamScoring.Item> items = apply(p, dto);
        p.setStatus(ExamCodes.PAPER_DRAFT);
        p.setCreatedBy(adminId);
        paperMapper.insert(p);
        insertQuestions(p.getId(), items);
        return p.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ExamDTOs.PaperSave dto) {
        OrgExamPaper p = new OrgExamPaper();
        List<ExamScoring.Item> items = apply(p, dto);
        int rows = paperMapper.update(null, Wrappers.<OrgExamPaper>lambdaUpdate()
                .eq(OrgExamPaper::getId, id)
                .eq(OrgExamPaper::getStatus, ExamCodes.PAPER_DRAFT)
                .set(OrgExamPaper::getTitle, p.getTitle())
                .set(OrgExamPaper::getDescription, p.getDescription())
                .set(OrgExamPaper::getPassScore, p.getPassScore())
                .set(OrgExamPaper::getTotalScore, p.getTotalScore())
                .set(OrgExamPaper::getQualificationMonths, p.getQualificationMonths())
                .set(OrgExamPaper::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("只有草稿可以修改（当前：" + ExamCodes.paperStatusLabel(require(id).getStatus())
                    + "）；要改题请复制出一份新草稿");
        }
        questionMapper.delete(Wrappers.<OrgExamQuestion>lambdaQuery().eq(OrgExamQuestion::getPaperId, id));
        insertQuestions(id, items);
    }

    /** 开放（草稿 → 开放中）。已有开放中的试卷时撞 {@code uk_active_open}。 */
    public void publish(Long id) {
        require(id);
        int rows;
        try {
            rows = paperMapper.update(null, Wrappers.<OrgExamPaper>lambdaUpdate()
                    .eq(OrgExamPaper::getId, id)
                    .eq(OrgExamPaper::getStatus, ExamCodes.PAPER_DRAFT)
                    .set(OrgExamPaper::getStatus, ExamCodes.PAPER_OPEN)
                    .set(OrgExamPaper::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已经有一份开放中的试卷，请先停止它再开放这一份");
        }
        if (rows != 1) {
            throw new BusinessException("只有草稿可以开放（当前：" + ExamCodes.paperStatusLabel(require(id).getStatus()) + "）");
        }
    }

    /** 停止（开放中 → 已停止）。已交的答卷照常阅卷。 */
    public void close(Long id) {
        int rows = paperMapper.update(null, Wrappers.<OrgExamPaper>lambdaUpdate()
                .eq(OrgExamPaper::getId, id)
                .eq(OrgExamPaper::getStatus, ExamCodes.PAPER_OPEN)
                .set(OrgExamPaper::getStatus, ExamCodes.PAPER_CLOSED)
                .set(OrgExamPaper::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("只有开放中的试卷可以停止（当前：" + ExamCodes.paperStatusLabel(require(id).getStatus()) + "）");
        }
    }

    /** 复制成一份新草稿（题目、分值、标准答案一并复制）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long id, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgExamPaper src = require(id);
        OrgExamPaper p = new OrgExamPaper();
        String title = src.getTitle() + "（副本）";
        p.setTitle(title.length() > 100 ? title.substring(0, 100) : title);
        p.setDescription(src.getDescription());
        p.setPassScore(src.getPassScore());
        p.setTotalScore(src.getTotalScore());
        p.setQualificationMonths(src.getQualificationMonths());
        p.setStatus(ExamCodes.PAPER_DRAFT);
        p.setCreatedBy(adminId);
        paperMapper.insert(p);
        for (OrgExamQuestion q : questionRows(id)) {
            OrgExamQuestion c = new OrgExamQuestion();
            c.setPaperId(p.getId());
            c.setSort(q.getSort());
            c.setQuestionType(q.getQuestionType());
            c.setTitle(q.getTitle());
            c.setDescription(q.getDescription());
            c.setOptionsJson(q.getOptionsJson());
            c.setConfigJson(q.getConfigJson());
            c.setAnswerJson(q.getAnswerJson());
            c.setScore(q.getScore());
            questionMapper.insert(c);
        }
        return p.getId();
    }

    /** 删除（仅草稿）。开放过的试卷挂着答卷，删了答卷就没有题面可对。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        int rows = paperMapper.delete(Wrappers.<OrgExamPaper>lambdaQuery()
                .eq(OrgExamPaper::getId, id)
                .eq(OrgExamPaper::getStatus, ExamCodes.PAPER_DRAFT));
        if (rows != 1) {
            throw new BusinessException("只有草稿可以删除（当前：" + ExamCodes.paperStatusLabel(require(id).getStatus()) + "）");
        }
        questionMapper.delete(Wrappers.<OrgExamQuestion>lambdaQuery().eq(OrgExamQuestion::getPaperId, id));
    }

    public PageResult<ExamVOs.Paper> list(PageQuery query, Integer status, String keyword) {
        IPage<OrgExamPaper> page = paperMapper.selectPage(query.toPage(), Wrappers.<OrgExamPaper>lambdaQuery()
                .eq(status != null, OrgExamPaper::getStatus, status)
                .like(StringUtils.hasText(keyword), OrgExamPaper::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(OrgExamPaper::getId));
        List<Long> ids = page.getRecords().stream().map(OrgExamPaper::getId).toList();
        Map<Long, Long> attempts = groupCount(ids, attemptMapper, "paper_id");
        Map<Long, Long> questions = groupCount(ids, questionMapper, "paper_id");
        return PageResult.of(page.convert(p -> {
            ExamVOs.Paper vo = toVO(p);
            vo.setAttemptCount(attempts.getOrDefault(p.getId(), 0L));
            vo.setQuestionCount(questions.getOrDefault(p.getId(), 0L).intValue());
            return vo;
        }));
    }

    /** 管理端详情：含标准答案。 */
    public ExamVOs.Paper detail(Long id) {
        OrgExamPaper p = require(id);
        ExamVOs.Paper vo = toVO(p);
        List<ExamVOs.Question> qs = items(id).stream().map(i -> questionVO(i, true)).toList();
        vo.setQuestions(qs);
        vo.setQuestionCount(qs.size());
        vo.setAttemptCount(groupCount(List.of(id), attemptMapper, "paper_id").getOrDefault(id, 0L));
        return vo;
    }

    // ================= 给同模块的服务用 =================

    public OrgExamPaper require(Long id) {
        OrgExamPaper p = id == null ? null : paperMapper.selectById(id);
        if (p == null) {
            throw new BusinessException("试卷不存在");
        }
        return p;
    }

    /** 当前开放中的那一份；没有为 null。 */
    public OrgExamPaper openPaper() {
        return paperMapper.selectOne(Wrappers.<OrgExamPaper>lambdaQuery()
                .eq(OrgExamPaper::getStatus, ExamCodes.PAPER_OPEN)
                .last("LIMIT 1"));
    }

    /** 按题号排好的题目（含分值与标准答案）。 */
    public List<ExamScoring.Item> items(Long paperId) {
        return ExamScoring.readAll(questionRows(paperId));
    }

    public static ExamVOs.Paper toVO(OrgExamPaper p) {
        ExamVOs.Paper vo = new ExamVOs.Paper();
        vo.setId(p.getId());
        vo.setTitle(p.getTitle());
        vo.setDescription(p.getDescription());
        vo.setPassScore(p.getPassScore());
        vo.setTotalScore(p.getTotalScore());
        vo.setQualificationMonths(p.getQualificationMonths());
        vo.setStatus(p.getStatus());
        vo.setStatusLabel(ExamCodes.paperStatusLabel(p.getStatus()));
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }

    /** @param withAnswer 是否下发标准答案（志愿者端一律不下发） */
    public static ExamVOs.Question questionVO(ExamScoring.Item i, boolean withAnswer) {
        FormAnswerValidator.Definition d = i.definition();
        ExamVOs.Question vo = new ExamVOs.Question();
        vo.setId(d.id());
        vo.setSort(d.sort());
        vo.setType(d.type());
        vo.setTypeLabel(QuestionType.label(d.type()));
        vo.setTitle(d.title());
        vo.setDescription(d.description());
        vo.setOptions(d.options());
        vo.setMaxLength(d.maxLength());
        vo.setScore(i.score());
        vo.setSubjective(i.subjective());
        if (withAnswer) {
            vo.setAnswer(i.answer());
            vo.setAnswerDisplay(i.answer() == null ? null : FormAnswerValidator.display(d, i.answer()));
        }
        return vo;
    }

    // ================= 内部 =================

    private List<OrgExamQuestion> questionRows(Long paperId) {
        return questionMapper.selectList(Wrappers.<OrgExamQuestion>lambdaQuery()
                .eq(OrgExamQuestion::getPaperId, paperId)
                .orderByAsc(OrgExamQuestion::getSort)
                .orderByAsc(OrgExamQuestion::getId));
    }

    private List<ExamScoring.Item> apply(OrgExamPaper p, ExamDTOs.PaperSave dto) {
        if (dto == null || !StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException("请填写试卷名称");
        }
        if (dto.getQuestions() == null || dto.getQuestions().isEmpty()) {
            throw new BusinessException("试卷至少要有一道题");
        }
        if (dto.getQuestions().size() > ExamCodes.QUESTIONS_MAX) {
            throw new BusinessException("一份试卷最多 " + ExamCodes.QUESTIONS_MAX + " 道题");
        }
        List<ExamScoring.Item> items = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < dto.getQuestions().size(); i++) {
            ExamScoring.Item item = ExamScoring.define(dto.getQuestions().get(i), i + 1);
            items.add(item);
            total += item.score();
        }
        if (dto.getPassScore() == null || dto.getPassScore() < 1 || dto.getPassScore() > total) {
            throw new BusinessException("及格线要在 1–" + total + " 分之间（满分 " + total + "）");
        }
        if (dto.getQualificationMonths() != null && (dto.getQualificationMonths() < 1 || dto.getQualificationMonths() > 120)) {
            throw new BusinessException("资格有效期要在 1–120 个月之间，不填为长期有效");
        }
        p.setTitle(dto.getTitle().trim());
        p.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
        p.setPassScore(dto.getPassScore());
        p.setTotalScore(total);
        p.setQualificationMonths(dto.getQualificationMonths());
        return items;
    }

    private void insertQuestions(Long paperId, List<ExamScoring.Item> items) {
        for (ExamScoring.Item i : items) {
            FormAnswerValidator.Definition d = i.definition();
            OrgExamQuestion q = new OrgExamQuestion();
            q.setPaperId(paperId);
            q.setSort(d.sort());
            q.setQuestionType(d.type());
            q.setTitle(d.title());
            q.setDescription(d.description());
            q.setOptionsJson(FormAnswerValidator.optionsJson(d));
            q.setConfigJson(FormAnswerValidator.configJson(d));
            q.setAnswerJson(ExamScoring.answerJson(i.answer()));
            q.setScore(i.score());
            questionMapper.insert(q);
        }
    }

    private static <T> Map<Long, Long> groupCount(List<Long> paperIds,
            com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper, String column) {
        Map<Long, Long> out = new HashMap<>();
        if (paperIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = mapper.selectMaps(new QueryWrapper<T>()
                .select(column + " AS pid", "COUNT(*) AS cnt")
                .in(column, paperIds)
                .groupBy(column));
        for (Map<String, Object> r : rows) {
            out.put(((Number) r.get("pid")).longValue(), ((Number) r.get("cnt")).longValue());
        }
        return out;
    }
}
