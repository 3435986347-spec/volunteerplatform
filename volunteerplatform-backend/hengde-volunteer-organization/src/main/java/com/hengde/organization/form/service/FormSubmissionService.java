package com.hengde.organization.form.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.form.dao.OrgFormMapper;
import com.hengde.organization.form.dao.OrgFormSubmissionMapper;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.entity.OrgForm;
import com.hengde.organization.form.entity.OrgFormSubmission;
import com.hengde.organization.form.support.FormAnswerValidator;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.vo.FormVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 问卷答卷（V4 问卷引擎批）：志愿者填写、管理端查看与导出，以及「随业务单据提交」的场景入口。
 *
 * <p><b>提交与停止收集串行</b>：提交的第一条语句是锁住问卷行的当前读（{@code FOR SHARE}），再判状态与时间窗——
 * 停止收集是对同一行的 UPDATE，二者排队，不会在停止之后还收进一份答卷。</p>
 *
 * <p><b>每人一次靠唯一键</b>（{@code uk_active_single}），不靠先查再插；撞键报「你已经提交过」。</p>
 *
 * <p><b>文件题只收本系统上传到 {@code form/} 目录的文件</b>（{@link FileStorageService#isOwnUpload}）。</p>
 *
 * @author hengde
 */
@Service
public class FormSubmissionService {

    /** 志愿者上传问卷附件的对象目录（与 api 的 {@code POST /v/files/form-file} 一致）。 */
    public static final String FILE_DIR = "form";
    /** 一次导出的上限，超了报错而不是静默截断。 */
    public static final int EXPORT_LIMIT = 20000;

    private OrgFormMapper formMapper;
    private OrgFormSubmissionMapper submissionMapper;
    private FormService formService;
    private VolunteerQueryService volunteerQueryService;
    private FileStorageService fileStorageService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setFormMapper(OrgFormMapper formMapper) {
        this.formMapper = formMapper;
    }

    @Autowired
    public void setSubmissionMapper(OrgFormSubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Autowired
    public void setFormService(FormService formService) {
        this.formService = formService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================= 志愿者端 =================

    /** 可以直接填写的问卷：通用 / 评优评先、收集中、在时间窗内；带「我是否已提交」。 */
    public PageResult<FormVOs.Form> listAvailable(Long volunteerId, PageQuery query) {
        LocalDateTime now = LocalDateTime.now();
        IPage<OrgForm> page = formMapper.selectPage(query.toPage(), Wrappers.<OrgForm>lambdaQuery()
                .in(OrgForm::getScene, FormFlow.LISTED_SCENES)
                .eq(OrgForm::getStatus, FormFlow.COLLECTING)
                .and(w -> w.isNull(OrgForm::getStartTime).or().le(OrgForm::getStartTime, now))
                .and(w -> w.isNull(OrgForm::getEndTime).or().gt(OrgForm::getEndTime, now))
                .orderByDesc(OrgForm::getPublishTime)
                .orderByDesc(OrgForm::getId));
        Set<Long> submitted = submittedFormIds(volunteerId,
                page.getRecords().stream().map(OrgForm::getId).toList());
        return PageResult.of(page.convert(f -> {
            FormVOs.Form vo = FormService.toVO(f);
            vo.setSubmitted(submitted.contains(f.getId()));
            return vo;
        }));
    }

    /** 问卷详情（含题目）：只看得到收集中的；已停止 / 草稿对志愿者等于不存在。 */
    public FormVOs.Form detailForVolunteer(Long formId, Long volunteerId) {
        OrgForm f = formId == null ? null : formMapper.selectById(formId);
        if (f == null || !Objects.equals(f.getStatus(), FormFlow.COLLECTING)) {
            throw new BusinessException("问卷不存在或已停止收集");
        }
        return withQuestions(f, volunteerId);
    }

    /**
     * 某个场景当前收集中的问卷（报名管理团队 / 评优评先的页面先取它来渲染）；没有返回 null。
     * 不看时间窗之外的——还没开始 / 已截止的问卷，渲染出来也交不上。
     */
    public FormVOs.Form currentForScene(int scene, Long volunteerId) {
        if (!FormFlow.isValidScene(scene) || scene == FormFlow.SCENE_GENERAL) {
            throw new BusinessException("通用问卷没有「当前问卷」，请从问卷列表进入");
        }
        OrgForm f = collecting(scene);
        if (f == null || !inWindow(f, LocalDateTime.now())) {
            return null;
        }
        return withQuestions(f, volunteerId);
    }

    /** 直接提交一份答卷（通用问卷 / 评优评先）。返回答卷 id。 */
    public Long submit(Long formId, Long volunteerId, FormDTOs.Submit dto) {
        if (volunteerId == null) {
            throw new BusinessException("未登录");
        }
        OrgForm f = formId == null ? null : formMapper.selectById(formId);
        if (f != null && !FormFlow.DIRECT_SUBMIT_SCENES.contains(f.getScene())) {
            throw new BusinessException("这份问卷要在「" + FormFlow.sceneLabel(f.getScene()) + "」里填写");
        }
        return transactionTemplate.execute(s -> doSubmit(formId, volunteerId, dto == null ? null : dto.getAnswers()));
    }

    /**
     * 随业务单据提交答卷（报名管理团队）：<b>必须在调用方的事务里调用</b>，答卷与单据同成同败。
     *
     * @return 答卷 id；该场景此刻没有收集中的问卷时返回 null（调用方照旧落单据）
     * @throws BusinessException 场景没有收集中的问卷、客户端却带了答案（他照着一份已停止的问卷填的，默默丢掉他的答案不对）
     */
    public Long submitForScene(int scene, Long volunteerId, Collection<FormDTOs.Answer> answers) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("submitForScene 必须在调用方事务内调用：答卷要与业务单据同成同败");
        }
        OrgForm f = collecting(scene);
        if (f == null || !inWindow(f, LocalDateTime.now())) {
            if (answers != null && !answers.isEmpty()) {
                throw new BusinessException("「" + FormFlow.sceneLabel(scene) + "」的问卷已停止收集，请刷新后重新填写");
            }
            return null;
        }
        return doSubmit(f.getId(), volunteerId, answers);
    }

    /** 我在这份问卷上的答卷（新的在前）。 */
    public List<FormVOs.Submission> mySubmissions(Long formId, Long volunteerId) {
        OrgForm f = formService.require(formId);
        List<FormAnswerValidator.Definition> defs = formService.definitions(formId);
        return submissionMapper.selectList(Wrappers.<OrgFormSubmission>lambdaQuery()
                        .eq(OrgFormSubmission::getFormId, formId)
                        .eq(OrgFormSubmission::getVolunteerId, volunteerId)
                        .orderByDesc(OrgFormSubmission::getId))
                .stream().map(s -> toVO(s, f, defs, null)).toList();
    }

    // ================= 管理端 =================

    public PageResult<FormVOs.Submission> listForAdmin(Long formId, PageQuery query) {
        OrgForm f = formService.require(formId);
        List<FormAnswerValidator.Definition> defs = formService.definitions(formId);
        IPage<OrgFormSubmission> page = submissionMapper.selectPage(query.toPage(),
                Wrappers.<OrgFormSubmission>lambdaQuery()
                        .eq(OrgFormSubmission::getFormId, formId)
                        .orderByDesc(OrgFormSubmission::getSubmitTime)
                        .orderByDesc(OrgFormSubmission::getId));
        Map<Long, VolunteerContactView> contacts = contactsOf(page.getRecords());
        return PageResult.of(page.convert(s -> toVO(s, f, defs, contacts)));
    }

    public FormVOs.Submission detailForAdmin(Long submissionId) {
        OrgFormSubmission s = submissionId == null ? null : submissionMapper.selectById(submissionId);
        if (s == null) {
            throw new BusinessException("答卷不存在");
        }
        OrgForm f = formService.require(s.getFormId());
        return toVO(s, f, formService.definitions(f.getId()), contactsOf(List.of(s)));
    }

    /** 某份答卷的逐题答案（报名管理团队审核页用）；答卷不存在返回空列表。 */
    public List<FormVOs.AnswerView> answerViews(Long submissionId) {
        OrgFormSubmission s = submissionId == null ? null : submissionMapper.selectById(submissionId);
        if (s == null) {
            return List.of();
        }
        return FormAnswerValidator.views(formService.definitions(s.getFormId()),
                FormAnswerValidator.fromJson(s.getAnswersJson()));
    }

    /** 一批答卷的逐题答案，一次查完（导出用）。 */
    public Map<Long, List<FormVOs.AnswerView>> answerViews(Collection<Long> submissionIds) {
        Map<Long, List<FormVOs.AnswerView>> out = new HashMap<>();
        if (submissionIds == null || submissionIds.isEmpty()) {
            return out;
        }
        Map<Long, List<FormAnswerValidator.Definition>> defsByForm = new HashMap<>();
        for (OrgFormSubmission s : submissionMapper.selectBatchIds(submissionIds)) {
            List<FormAnswerValidator.Definition> defs = defsByForm.computeIfAbsent(s.getFormId(),
                    formService::definitions);
            out.put(s.getId(), FormAnswerValidator.views(defs, FormAnswerValidator.fromJson(s.getAnswersJson())));
        }
        return out;
    }

    /**
     * 导出表：表头「提交编号 / 姓名 / 手机号 / 提交时间 / 1. 题目 …」，每份答卷一行。
     * 超过 {@link #EXPORT_LIMIT} 份报错，不静默截断。
     */
    public ExportTable exportTable(Long formId) {
        OrgForm f = formService.require(formId);
        List<FormAnswerValidator.Definition> defs = formService.definitions(formId);
        Long total = submissionMapper.selectCount(Wrappers.<OrgFormSubmission>lambdaQuery()
                .eq(OrgFormSubmission::getFormId, formId));
        if (total != null && total > EXPORT_LIMIT) {
            throw new BusinessException("答卷超过 " + EXPORT_LIMIT + " 份，一次导不完，请联系管理员分批导出");
        }
        List<OrgFormSubmission> rows = submissionMapper.selectList(Wrappers.<OrgFormSubmission>lambdaQuery()
                .eq(OrgFormSubmission::getFormId, formId)
                .orderByAsc(OrgFormSubmission::getSubmitTime)
                .orderByAsc(OrgFormSubmission::getId));
        Map<Long, VolunteerContactView> contacts = contactsOf(rows);
        List<String> head = new ArrayList<>(List.of("提交编号", "姓名", "手机号", "提交时间"));
        defs.forEach(d -> head.add(d.sort() + ". " + d.title()));
        List<List<String>> body = new ArrayList<>(rows.size());
        for (OrgFormSubmission s : rows) {
            VolunteerContactView c = contacts.get(s.getVolunteerId());
            List<String> line = new ArrayList<>(head.size());
            line.add(String.valueOf(s.getId()));
            line.add(c == null || c.realName() == null ? "" : c.realName());
            line.add(c == null || c.phone() == null ? "" : c.phone());
            line.add(s.getSubmitTime() == null ? "" : s.getSubmitTime().toString().replace('T', ' '));
            Map<Long, Object> answers = FormAnswerValidator.fromJson(s.getAnswersJson());
            defs.forEach(d -> line.add(FormAnswerValidator.display(d, answers.get(d.id()))));
            body.add(line);
        }
        return new ExportTable(f.getTitle(), head, body);
    }

    /** 导出用的二维表。 */
    public record ExportTable(String title, List<String> head, List<List<String>> rows) {
    }

    // ================= 内部 =================

    private Long doSubmit(Long formId, Long volunteerId, Collection<FormDTOs.Answer> answers) {
        OrgForm f = formId == null ? null : formMapper.selectByIdForShare(formId);
        if (f == null || !Objects.equals(f.getStatus(), FormFlow.COLLECTING)) {
            throw new BusinessException("问卷不存在或已停止收集");
        }
        LocalDateTime now = LocalDateTime.now();
        if (f.getStartTime() != null && now.isBefore(f.getStartTime())) {
            throw new BusinessException("问卷还没有开始收集");
        }
        if (f.getEndTime() != null && !now.isBefore(f.getEndTime())) {
            throw new BusinessException("问卷已经截止");
        }
        if (!volunteerQueryService.isActive(volunteerId)) {
            throw new BusinessException("账号状态异常，无法填写");
        }
        if (Objects.equals(f.getRequireRegistered(), 1)
                && !volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("请先完成实名注册再填写");
        }
        List<FormAnswerValidator.Normalized> normalized = FormAnswerValidator.validate(
                formService.definitions(f.getId()), answers,
                url -> fileStorageService.isOwnUpload(url, FILE_DIR));
        OrgFormSubmission s = new OrgFormSubmission();
        s.setFormId(f.getId());
        s.setScene(f.getScene());
        s.setVolunteerId(volunteerId);
        s.setSingleSubmit(f.getSingleSubmit());
        s.setAnswersJson(FormAnswerValidator.toJson(normalized));
        s.setSubmitTime(now);
        try {
            submissionMapper.insert(s);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("你已经提交过这份问卷了");
        }
        return s.getId();
    }

    private OrgForm collecting(int scene) {
        List<OrgForm> rows = formMapper.selectList(Wrappers.<OrgForm>lambdaQuery()
                .eq(OrgForm::getScene, scene)
                .eq(OrgForm::getStatus, FormFlow.COLLECTING)
                .orderByDesc(OrgForm::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean inWindow(OrgForm f, LocalDateTime now) {
        return (f.getStartTime() == null || !now.isBefore(f.getStartTime()))
                && (f.getEndTime() == null || now.isBefore(f.getEndTime()));
    }

    private FormVOs.Form withQuestions(OrgForm f, Long volunteerId) {
        FormVOs.Form vo = FormService.toVO(f);
        List<FormVOs.Question> qs = formService.definitions(f.getId()).stream().map(FormAnswerValidator::toVO).toList();
        vo.setQuestions(qs);
        vo.setQuestionCount(qs.size());
        vo.setSubmitted(volunteerId != null && submittedFormIds(volunteerId, List.of(f.getId())).contains(f.getId()));
        return vo;
    }

    private Set<Long> submittedFormIds(Long volunteerId, List<Long> formIds) {
        if (volunteerId == null || formIds.isEmpty()) {
            return Set.of();
        }
        return submissionMapper.selectList(Wrappers.<OrgFormSubmission>lambdaQuery()
                        .select(OrgFormSubmission::getFormId)
                        .eq(OrgFormSubmission::getVolunteerId, volunteerId)
                        .in(OrgFormSubmission::getFormId, formIds))
                .stream().map(OrgFormSubmission::getFormId).collect(Collectors.toCollection(HashSet::new));
    }

    private Map<Long, VolunteerContactView> contactsOf(List<OrgFormSubmission> rows) {
        Set<Long> ids = rows.stream().map(OrgFormSubmission::getVolunteerId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : volunteerQueryService.listContactsByIds(ids);
    }

    private static FormVOs.Submission toVO(OrgFormSubmission s, OrgForm f, List<FormAnswerValidator.Definition> defs,
                                           Map<Long, VolunteerContactView> contacts) {
        FormVOs.Submission vo = new FormVOs.Submission();
        vo.setId(s.getId());
        vo.setFormId(s.getFormId());
        vo.setFormTitle(f.getTitle());
        vo.setScene(s.getScene());
        vo.setSubmitTime(s.getSubmitTime());
        if (contacts != null) {
            vo.setVolunteerId(s.getVolunteerId());
            VolunteerContactView c = contacts.get(s.getVolunteerId());
            if (c != null) {
                vo.setVolunteerName(c.realName());
                vo.setVolunteerPhone(c.phone());
            }
        }
        vo.setAnswers(FormAnswerValidator.views(defs, FormAnswerValidator.fromJson(s.getAnswersJson())));
        return vo;
    }
}
