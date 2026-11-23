package com.hengde.organization.form.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.form.dao.OrgFormMapper;
import com.hengde.organization.form.dao.OrgFormQuestionMapper;
import com.hengde.organization.form.dao.OrgFormSubmissionMapper;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.entity.OrgForm;
import com.hengde.organization.form.entity.OrgFormQuestion;
import com.hengde.organization.form.entity.OrgFormSubmission;
import com.hengde.organization.form.support.FormAnswerValidator;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.vo.FormVOs;
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
import java.util.Objects;

/**
 * 问卷管理（V4 问卷引擎批，管理端）：建 / 改草稿 / 发布 / 停止 / 复制 / 删草稿。
 *
 * <p><b>题目只在草稿时能改</b>：发布之后有人按这套题作答，再改题会让旧答卷的含义跟着变。
 * 所以「只能改草稿」写在 UPDATE 的 WHERE 里（与发布互斥：发布是同一行的 CAS），
 * 收集中 / 已停止的要改就复制出一份新草稿。</p>
 *
 * <p><b>一个场景同时至多一份收集中的问卷</b>（通用问卷除外）由 {@code uk_active_scene} 兜底，发布撞键即报「请先停止它」。</p>
 *
 * @author hengde
 */
@Service
public class FormService {

    private OrgFormMapper formMapper;
    private OrgFormQuestionMapper questionMapper;
    private OrgFormSubmissionMapper submissionMapper;

    @Autowired
    public void setFormMapper(OrgFormMapper formMapper) {
        this.formMapper = formMapper;
    }

    @Autowired
    public void setQuestionMapper(OrgFormQuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    @Autowired
    public void setSubmissionMapper(OrgFormSubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(FormDTOs.Save dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgForm f = new OrgForm();
        List<FormAnswerValidator.Definition> defs = apply(f, dto);
        f.setStatus(FormFlow.DRAFT);
        f.setCreateBy(adminId);
        formMapper.insert(f);
        insertQuestions(f.getId(), defs);
        return f.getId();
    }

    /** 修改草稿：条件写在 UPDATE 的 WHERE 里（只有草稿能改），再整批替换题目。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, FormDTOs.Save dto) {
        OrgForm f = new OrgForm();
        List<FormAnswerValidator.Definition> defs = apply(f, dto);
        int rows = formMapper.update(null, Wrappers.<OrgForm>lambdaUpdate()
                .eq(OrgForm::getId, id)
                .eq(OrgForm::getStatus, FormFlow.DRAFT)
                .set(OrgForm::getScene, f.getScene())
                .set(OrgForm::getTitle, f.getTitle())
                .set(OrgForm::getDescription, f.getDescription())
                .set(OrgForm::getStartTime, f.getStartTime())
                .set(OrgForm::getEndTime, f.getEndTime())
                .set(OrgForm::getSingleSubmit, f.getSingleSubmit())
                .set(OrgForm::getRequireRegistered, f.getRequireRegistered())
                .set(OrgForm::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("只有草稿可以修改（当前：" + FormFlow.statusLabel(require(id).getStatus())
                    + "）；要改题请复制出一份新草稿");
        }
        questionMapper.delete(Wrappers.<OrgFormQuestion>lambdaQuery().eq(OrgFormQuestion::getFormId, id));
        insertQuestions(id, defs);
    }

    /** 发布（草稿 → 收集中）。同场景已有收集中的问卷时撞 {@code uk_active_scene}。 */
    public void publish(Long id, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgForm f = require(id);
        LocalDateTime now = LocalDateTime.now();
        if (f.getEndTime() != null && !f.getEndTime().isAfter(now)) {
            throw new BusinessException("截止时间已经过了，请先修改截止时间");
        }
        int rows;
        try {
            rows = formMapper.update(null, Wrappers.<OrgForm>lambdaUpdate()
                    .eq(OrgForm::getId, id)
                    .eq(OrgForm::getStatus, FormFlow.DRAFT)
                    .set(OrgForm::getStatus, FormFlow.COLLECTING)
                    .set(OrgForm::getPublishTime, now)
                    .set(OrgForm::getPublishBy, adminId)
                    .set(OrgForm::getUpdateTime, now));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("「" + FormFlow.sceneLabel(f.getScene())
                    + "」已经有一份正在收集的问卷，请先停止它再发布");
        }
        if (rows != 1) {
            throw new BusinessException("只有草稿可以发布（当前：" + FormFlow.statusLabel(require(id).getStatus()) + "）");
        }
    }

    /** 停止收集（收集中 → 已停止）。已收到的答卷不受影响。 */
    public void close(Long id) {
        LocalDateTime now = LocalDateTime.now();
        int rows = formMapper.update(null, Wrappers.<OrgForm>lambdaUpdate()
                .eq(OrgForm::getId, id)
                .eq(OrgForm::getStatus, FormFlow.COLLECTING)
                .set(OrgForm::getStatus, FormFlow.CLOSED)
                .set(OrgForm::getCloseTime, now)
                .set(OrgForm::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("只有收集中的问卷可以停止（当前：" + FormFlow.statusLabel(require(id).getStatus()) + "）");
        }
    }

    /** 复制成一份新草稿（题目一并复制）——收集中 / 已停止的问卷要改题走这里。 */
    @Transactional(rollbackFor = Exception.class)
    public Long copy(Long id, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        OrgForm src = require(id);
        OrgForm f = new OrgForm();
        f.setScene(src.getScene());
        String title = src.getTitle() + "（副本）";
        f.setTitle(title.length() > 128 ? title.substring(0, 128) : title);
        f.setDescription(src.getDescription());
        f.setStartTime(src.getStartTime());
        f.setEndTime(src.getEndTime());
        f.setSingleSubmit(src.getSingleSubmit());
        f.setRequireRegistered(src.getRequireRegistered());
        f.setStatus(FormFlow.DRAFT);
        f.setCreateBy(adminId);
        formMapper.insert(f);
        for (OrgFormQuestion q : questionRows(id)) {
            OrgFormQuestion c = new OrgFormQuestion();
            c.setFormId(f.getId());
            c.setSort(q.getSort());
            c.setQuestionType(q.getQuestionType());
            c.setTitle(q.getTitle());
            c.setDescription(q.getDescription());
            c.setRequired(q.getRequired());
            c.setOptionsJson(q.getOptionsJson());
            c.setConfigJson(q.getConfigJson());
            questionMapper.insert(c);
        }
        return f.getId();
    }

    /** 删除（仅草稿）。发布过的问卷挂着答卷，删了答卷就没有题面可对。 */
    public void delete(Long id) {
        int rows = formMapper.delete(Wrappers.<OrgForm>lambdaQuery()
                .eq(OrgForm::getId, id)
                .eq(OrgForm::getStatus, FormFlow.DRAFT));
        if (rows != 1) {
            throw new BusinessException("只有草稿可以删除（当前：" + FormFlow.statusLabel(require(id).getStatus()) + "）");
        }
    }

    public PageResult<FormVOs.Form> list(PageQuery query, Integer scene, Integer status, String keyword) {
        IPage<OrgForm> page = formMapper.selectPage(query.toPage(), Wrappers.<OrgForm>lambdaQuery()
                .eq(scene != null, OrgForm::getScene, scene)
                .eq(status != null, OrgForm::getStatus, status)
                .like(StringUtils.hasText(keyword), OrgForm::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(OrgForm::getId));
        List<Long> ids = page.getRecords().stream().map(OrgForm::getId).toList();
        Map<Long, Long> counts = submissionCounts(ids);
        Map<Long, Long> questionCounts = questionCounts(ids);
        return PageResult.of(page.convert(f -> {
            FormVOs.Form vo = toVO(f);
            vo.setSubmissionCount(counts.getOrDefault(f.getId(), 0L));
            vo.setQuestionCount(questionCounts.getOrDefault(f.getId(), 0L).intValue());
            return vo;
        }));
    }

    public FormVOs.Form detail(Long id) {
        OrgForm f = require(id);
        FormVOs.Form vo = toVO(f);
        List<FormVOs.Question> qs = definitions(id).stream().map(FormAnswerValidator::toVO).toList();
        vo.setQuestions(qs);
        vo.setQuestionCount(qs.size());
        vo.setSubmissionCount(submissionCounts(List.of(id)).getOrDefault(id, 0L));
        return vo;
    }

    // ================= 给同模块的服务用 =================

    public OrgForm require(Long id) {
        OrgForm f = id == null ? null : formMapper.selectById(id);
        if (f == null) {
            throw new BusinessException("问卷不存在");
        }
        return f;
    }

    /** 按题号排好的题目定义。 */
    public List<FormAnswerValidator.Definition> definitions(Long formId) {
        return questionRows(formId).stream().map(q -> FormAnswerValidator.read(q.getId(), q.getSort(),
                q.getQuestionType(), q.getTitle(), q.getDescription(), Objects.equals(q.getRequired(), 1),
                q.getOptionsJson(), q.getConfigJson())).toList();
    }

    public static FormVOs.Form toVO(OrgForm f) {
        FormVOs.Form vo = new FormVOs.Form();
        vo.setId(f.getId());
        vo.setScene(f.getScene());
        vo.setSceneLabel(FormFlow.sceneLabel(f.getScene()));
        vo.setTitle(f.getTitle());
        vo.setDescription(f.getDescription());
        vo.setStatus(f.getStatus());
        vo.setStatusLabel(FormFlow.statusLabel(f.getStatus()));
        vo.setStartTime(f.getStartTime());
        vo.setEndTime(f.getEndTime());
        vo.setSingleSubmit(Objects.equals(f.getSingleSubmit(), 1));
        vo.setRequireRegistered(Objects.equals(f.getRequireRegistered(), 1));
        vo.setPublishTime(f.getPublishTime());
        vo.setCloseTime(f.getCloseTime());
        vo.setCreateTime(f.getCreateTime());
        return vo;
    }

    // ================= 内部 =================

    private List<OrgFormQuestion> questionRows(Long formId) {
        return questionMapper.selectList(Wrappers.<OrgFormQuestion>lambdaQuery()
                .eq(OrgFormQuestion::getFormId, formId)
                .orderByAsc(OrgFormQuestion::getSort)
                .orderByAsc(OrgFormQuestion::getId));
    }

    private List<FormAnswerValidator.Definition> apply(OrgForm f, FormDTOs.Save dto) {
        if (dto == null || !StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException("请填写问卷标题");
        }
        int scene = dto.getScene() == null ? FormFlow.SCENE_GENERAL : dto.getScene();
        if (!FormFlow.isValidScene(scene)) {
            throw new BusinessException("场景只能是 1通用问卷 / 2报名管理团队 / 3评优评先 / 4意见反馈 / 5投诉建议");
        }
        if (dto.getStartTime() != null && dto.getEndTime() != null && !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("截止时间须晚于开始时间");
        }
        boolean single;
        if (scene == FormFlow.SCENE_MANAGER_APPLICATION) {
            // 答卷随申请提交：被驳回后可以再申请，「每人一次」会把第二次申请挡在唯一键上
            if (Boolean.TRUE.equals(dto.getSingleSubmit())) {
                throw new BusinessException("报名管理团队的问卷不能设为每人一次——提交次数由申请流程控制（驳回后可以再申请）");
            }
            single = false;
        } else if (dto.getSingleSubmit() == null) {
            // 不传时：意见反馈默认不限次数（每次有话都能说），其余默认每人一次
            single = scene != FormFlow.SCENE_FEEDBACK;
        } else {
            single = dto.getSingleSubmit();
        }
        if (dto.getQuestions() == null || dto.getQuestions().isEmpty()) {
            throw new BusinessException("问卷至少要有一道题");
        }
        if (dto.getQuestions().size() > 100) {
            throw new BusinessException("一份问卷最多 100 道题");
        }
        List<FormAnswerValidator.Definition> defs = new ArrayList<>();
        for (int i = 0; i < dto.getQuestions().size(); i++) {
            defs.add(FormAnswerValidator.define(dto.getQuestions().get(i), i + 1));
        }
        f.setScene(scene);
        f.setTitle(dto.getTitle().trim());
        f.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
        f.setStartTime(dto.getStartTime());
        f.setEndTime(dto.getEndTime());
        f.setSingleSubmit(single ? 1 : 0);
        f.setRequireRegistered(Boolean.FALSE.equals(dto.getRequireRegistered()) ? 0 : 1);
        return defs;
    }

    private void insertQuestions(Long formId, List<FormAnswerValidator.Definition> defs) {
        for (FormAnswerValidator.Definition d : defs) {
            OrgFormQuestion q = new OrgFormQuestion();
            q.setFormId(formId);
            q.setSort(d.sort());
            q.setQuestionType(d.type());
            q.setTitle(d.title());
            q.setDescription(d.description());
            q.setRequired(d.required() ? 1 : 0);
            q.setOptionsJson(FormAnswerValidator.optionsJson(d));
            q.setConfigJson(FormAnswerValidator.configJson(d));
            questionMapper.insert(q);
        }
    }

    /** 各问卷的答卷数，一页一次查。 */
    private Map<Long, Long> submissionCounts(List<Long> formIds) {
        if (formIds.isEmpty()) {
            return new HashMap<>();
        }
        return groupCount(submissionMapper.selectMaps(new QueryWrapper<OrgFormSubmission>()
                .select("form_id AS formId", "COUNT(*) AS cnt")
                .in("form_id", formIds)
                .groupBy("form_id")));
    }

    private Map<Long, Long> questionCounts(List<Long> formIds) {
        if (formIds.isEmpty()) {
            return new HashMap<>();
        }
        return groupCount(questionMapper.selectMaps(new QueryWrapper<OrgFormQuestion>()
                .select("form_id AS formId", "COUNT(*) AS cnt")
                .in("form_id", formIds)
                .groupBy("form_id")));
    }

    private static Map<Long, Long> groupCount(List<Map<String, Object>> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(((Number) r.get("formId")).longValue(), ((Number) r.get("cnt")).longValue());
        }
        return out;
    }
}
