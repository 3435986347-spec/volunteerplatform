package com.hengde.data.complaint.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.NotificationService;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.data.complaint.dao.DataComplaintLogMapper;
import com.hengde.data.complaint.dao.DataComplaintMapper;
import com.hengde.data.complaint.dto.ComplaintDTOs;
import com.hengde.data.complaint.entity.DataComplaint;
import com.hengde.data.complaint.entity.DataComplaintLog;
import com.hengde.data.complaint.support.ComplaintFlow;
import com.hengde.data.complaint.support.ComplaintProperties;
import com.hengde.data.complaint.vo.ComplaintVOs;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.FormFlow;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 投诉建议（Row 43「类似于问卷收集 / 投诉默认到监察部，后续监察部可根据工作需要选择流转到其他部门，含处理进度」，V4 投诉建议批）。
 *
 * <p><b>工单在谁手上谁看</b>：持 {@code data:complaint} 的账号只看得到、只动得了「当前在本部门」的工单；
 * 持 {@code data:complaint-all}（默认给监察部）的看全部、可代任何部门处理。部门就是 {@code admin_user.department} 那段文字，
 * 流转只能转给<b>当前有启用账号</b>的部门——转过去一定有人看得到。</p>
 *
 * <p><b>状态迁移全是 CAS，条件里带「当前部门」</b>：受理 / 流转 / 答复并发时只有一个成功；
 * 输家的报错<b>在事务之外重新读一次</b>再说明「刚被处理成了什么」——在事务里用普通读复核只会读到旧快照
 * （V3 反复撞过的那条：CAS 失败之后的复核读必须是当前读）。</p>
 *
 * <p><b>答复即办结</b>，同一事务写站内提示；短信 {@code COMPLAINT_REPLIED} 由 {@code SmsNotifyService} 等事务提交后发，
 * 只放答复的开头（短信变量有长度限制），全文在工单详情。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class ComplaintService {

    /** 后台操作人的可见范围：账号、所属部门、是否全部门。由控制器按登录态与权限算好传进来，服务层不碰 Sa-Token。 */
    public record Scope(Long adminId, String department, boolean allDepartments) {
    }

    private static final String LOCK_PREFIX = "lock:complaint:volunteer:";
    private static final String IMAGE_DIR = FormSubmissionService.FILE_DIR;
    private static final int MAX_IMAGES = 6;
    private static final int URL_MAX = 512;
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DataComplaintMapper complaintMapper;
    private DataComplaintLogMapper logMapper;
    private VolunteerQueryService volunteerQueryService;
    private AdminQueryService adminQueryService;
    private NotificationService notificationService;
    private SmsNotifyService smsNotifyService;
    private FormSubmissionService formSubmissionService;
    private FileStorageService fileStorageService;
    private ComplaintProperties properties;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setComplaintMapper(DataComplaintMapper complaintMapper) {
        this.complaintMapper = complaintMapper;
    }

    @Autowired
    public void setLogMapper(DataComplaintLogMapper logMapper) {
        this.logMapper = logMapper;
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
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    @Autowired
    public void setFormSubmissionService(FormSubmissionService formSubmissionService) {
        this.formSubmissionService = formSubmissionService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setProperties(ComplaintProperties properties) {
        this.properties = properties;
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

    /**
     * 提交一条投诉建议，进默认部门（监察部）。按人上锁：24 小时条数限制是「先数再插」，不锁的话连点能越过上限。
     * 协会为「投诉建议」场景发布了问卷时，答卷与工单同一事务落库。
     */
    public Long submit(Long volunteerId, ComplaintDTOs.Submit dto) {
        if (volunteerId == null) {
            throw new BusinessException("未登录");
        }
        if (dto == null || !ComplaintFlow.isValidType(dto.getType())) {
            throw new BusinessException("类型只能是 1投诉 / 2建议");
        }
        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        if (content.isEmpty()) {
            throw new BusinessException("请填写内容");
        }
        if (content.codePointCount(0, content.length()) > 2000) {
            throw new BusinessException("内容不超过 2000 字");
        }
        String images = checkImages(dto.getImages());
        if (!StringUtils.hasText(properties.getDefaultDepartment())) {
            throw new BusinessException("投诉建议暂未开放（没有配置受理部门）");
        }
        return DistributedLockSupport.runLocked(redissonClient, LOCK_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doSubmit(volunteerId, dto, content, images)));
    }

    private Long doSubmit(Long volunteerId, ComplaintDTOs.Submit dto, String content, String images) {
        if (!volunteerQueryService.isActive(volunteerId)) {
            throw new BusinessException("账号状态异常，无法提交");
        }
        LocalDateTime now = LocalDateTime.now();
        Long recent = complaintMapper.selectCount(Wrappers.<DataComplaint>lambdaQuery()
                .eq(DataComplaint::getVolunteerId, volunteerId)
                .ge(DataComplaint::getCreateTime, now.minusHours(24)));
        if (recent != null && recent >= properties.getDailyLimit()) {
            throw new BusinessException("24 小时内最多提交 " + properties.getDailyLimit() + " 条投诉建议，请稍后再试");
        }
        Long formSubmissionId = formSubmissionService.submitForScene(FormFlow.SCENE_COMPLAINT, volunteerId,
                dto.getAnswers());
        String dept = properties.getDefaultDepartment().trim();
        DataComplaint c = new DataComplaint();
        c.setVolunteerId(volunteerId);
        c.setComplaintType(dto.getType());
        c.setContent(content);
        c.setImages(images);
        c.setFormSubmissionId(formSubmissionId);
        c.setStatus(ComplaintFlow.PENDING);
        c.setCurrentDepartment(dept);
        c.setTransferCount(0);
        insertWithNumber(c, now);
        log(c.getId(), ComplaintFlow.ACT_SUBMIT, null, dept, null, true, ComplaintFlow.OP_VOLUNTEER, volunteerId);
        return c.getId();
    }

    /** 编号「TS + 秒 + 4 位随机」，撞 {@code uk_complaint_no} 换号重试（同一秒的并发提交会撞）。 */
    private void insertWithNumber(DataComplaint c, LocalDateTime now) {
        for (int attempt = 1; ; attempt++) {
            c.setComplaintNo("TS" + now.format(NO_FMT) + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
            try {
                complaintMapper.insert(c);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= 5) {
                    throw new BusinessException("提交的人太多了，请稍后再试");
                }
            }
        }
    }

    public PageResult<ComplaintVOs.Complaint> mine(Long volunteerId, PageQuery query) {
        IPage<DataComplaint> page = complaintMapper.selectPage(query.toPage(), Wrappers.<DataComplaint>lambdaQuery()
                .eq(DataComplaint::getVolunteerId, volunteerId)
                .orderByDesc(DataComplaint::getId));
        return PageResult.of(page.convert(ComplaintService::toVO));
    }

    /** 我的工单详情：处理进度只含对志愿者可见的几步，流转理由与内部备注不下发。别人的与不存在同一句话。 */
    public ComplaintVOs.Complaint detailMine(Long id, Long volunteerId) {
        DataComplaint c = id == null ? null : complaintMapper.selectById(id);
        if (c == null || !Objects.equals(c.getVolunteerId(), volunteerId)) {
            throw new BusinessException("工单不存在");
        }
        ComplaintVOs.Complaint vo = toVO(c);
        vo.setProgress(logs(id, true).stream().map(l -> progress(l, false, Map.of())).toList());
        vo.setFormAnswers(c.getFormSubmissionId() == null ? List.of()
                : formSubmissionService.answerViews(c.getFormSubmissionId()));
        return vo;
    }

    // ================= 管理端 =================

    /** 当前有启用账号的部门（流转下拉框）。 */
    public List<String> departments() {
        return adminQueryService.activeDepartments();
    }

    public PageResult<ComplaintVOs.Complaint> listForAdmin(Scope scope, PageQuery query, Integer status, Integer type,
                                                           String department, String keyword) {
        requireAdmin(scope);
        if (!scope.allDepartments()) {
            if (scope.department() == null) {
                return PageResult.of(List.of(), 0, query.getPage(), query.getSize());
            }
            department = scope.department();
        }
        String kw = keyword == null ? null : keyword.trim();
        IPage<DataComplaint> page = complaintMapper.selectPage(query.toPage(), Wrappers.<DataComplaint>lambdaQuery()
                .eq(status != null, DataComplaint::getStatus, status)
                .eq(type != null, DataComplaint::getComplaintType, type)
                .eq(StringUtils.hasText(department), DataComplaint::getCurrentDepartment, department)
                .and(StringUtils.hasText(kw), w -> w.eq(DataComplaint::getComplaintNo, kw).or()
                        .like(DataComplaint::getContent, kw))
                .orderByDesc(DataComplaint::getId));
        Set<Long> vids = page.getRecords().stream().map(DataComplaint::getVolunteerId).collect(Collectors.toSet());
        Map<Long, String> names = vids.isEmpty() ? Map.of() : volunteerQueryService.listNamesByIds(vids);
        return PageResult.of(page.convert(c -> {
            ComplaintVOs.Complaint vo = toVO(c);
            vo.setVolunteerId(c.getVolunteerId());
            vo.setVolunteerName(names.get(c.getVolunteerId()));
            return vo;
        }));
    }

    public ComplaintVOs.Complaint detailForAdmin(Scope scope, Long id) {
        DataComplaint c = requireVisible(scope, id);
        ComplaintVOs.Complaint vo = toVO(c);
        vo.setVolunteerId(c.getVolunteerId());
        VolunteerContactView contact = volunteerQueryService.listContactsByIds(Set.of(c.getVolunteerId()))
                .get(c.getVolunteerId());
        if (contact != null) {
            vo.setVolunteerName(contact.realName());
            vo.setVolunteerPhone(contact.phone());
        }
        List<DataComplaintLog> logs = logs(id, false);
        Set<Long> adminIds = logs.stream().filter(l -> l.getOperatorType() == ComplaintFlow.OP_ADMIN)
                .map(DataComplaintLog::getOperatorId).collect(Collectors.toCollection(HashSet::new));
        if (c.getAcceptBy() != null) {
            adminIds.add(c.getAcceptBy());
        }
        Map<Long, String> adminNames = adminQueryService.listNamesByIds(adminIds);
        vo.setAcceptByName(c.getAcceptBy() == null ? null : adminNames.get(c.getAcceptBy()));
        String volunteerName = vo.getVolunteerName();
        vo.setProgress(logs.stream().map(l -> {
            ComplaintVOs.Progress p = progress(l, true, adminNames);
            if (l.getOperatorType() == ComplaintFlow.OP_VOLUNTEER) {
                p.setOperatorName(volunteerName);
            }
            return p;
        }).toList());
        vo.setFormAnswers(c.getFormSubmissionId() == null ? List.of()
                : formSubmissionService.answerViews(c.getFormSubmissionId()));
        return vo;
    }

    /** 受理（待受理 → 处理中）。 */
    public void accept(Scope scope, Long id) {
        DataComplaint c = requireVisible(scope, id);
        if (!Objects.equals(c.getStatus(), ComplaintFlow.PENDING)) {
            throw new BusinessException("只有待受理的工单可以受理（当前：" + ComplaintFlow.statusLabel(c.getStatus()) + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean done = Boolean.TRUE.equals(transactionTemplate.execute(s -> {
            int rows = complaintMapper.update(null, casOn(c).eq(DataComplaint::getStatus, ComplaintFlow.PENDING)
                    .set(DataComplaint::getStatus, ComplaintFlow.PROCESSING)
                    .set(DataComplaint::getAcceptBy, scope.adminId())
                    .set(DataComplaint::getAcceptTime, now)
                    .set(DataComplaint::getUpdateTime, now));
            if (rows != 1) {
                return false;
            }
            log(id, ComplaintFlow.ACT_ACCEPT, null, c.getCurrentDepartment(), null, true, ComplaintFlow.OP_ADMIN,
                    scope.adminId());
            return true;
        }));
        if (!done) {
            throw conflict(id);
        }
    }

    /**
     * 流转到另一个部门（Row 43「选择流转到其他部门」）：回到待受理、清掉受理人，理由只给后台看。
     * 目标必须是当前有启用账号的部门。
     */
    public void transfer(Scope scope, Long id, String department, String reason) {
        DataComplaint c = requireVisible(scope, id);
        String to = department == null ? "" : department.trim();
        if (!departments().contains(to)) {
            throw new BusinessException("「" + to + "」没有启用的后台账号，转过去没人看得到");
        }
        if (to.equals(c.getCurrentDepartment())) {
            throw new BusinessException("工单已经在「" + to + "」了");
        }
        if (Objects.equals(c.getStatus(), ComplaintFlow.CLOSED)) {
            throw new BusinessException("已办结的工单不能再流转");
        }
        String note = StringUtils.hasText(reason) ? reason.trim() : null;
        LocalDateTime now = LocalDateTime.now();
        boolean done = Boolean.TRUE.equals(transactionTemplate.execute(s -> {
            int rows = complaintMapper.update(null, casOn(c)
                    .in(DataComplaint::getStatus, ComplaintFlow.PENDING, ComplaintFlow.PROCESSING)
                    .set(DataComplaint::getCurrentDepartment, to)
                    .set(DataComplaint::getStatus, ComplaintFlow.PENDING)
                    .set(DataComplaint::getAcceptBy, null)
                    .set(DataComplaint::getAcceptTime, null)
                    .setSql("transfer_count = transfer_count + 1")
                    .set(DataComplaint::getUpdateTime, now));
            if (rows != 1) {
                return false;
            }
            log(id, ComplaintFlow.ACT_TRANSFER, c.getCurrentDepartment(), to, note, true, ComplaintFlow.OP_ADMIN,
                    scope.adminId());
            return true;
        }));
        if (!done) {
            throw conflict(id);
        }
    }

    /** 答复并办结：同一事务写站内提示，短信等事务提交后发（只放答复开头）。 */
    public void reply(Scope scope, Long id, String content) {
        DataComplaint c = requireVisible(scope, id);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException("请填写答复内容");
        }
        if (text.codePointCount(0, text.length()) > 2000) {
            throw new BusinessException("答复不超过 2000 字");
        }
        if (Objects.equals(c.getStatus(), ComplaintFlow.CLOSED)) {
            throw new BusinessException("工单已经办结");
        }
        LocalDateTime now = LocalDateTime.now();
        String dept = c.getCurrentDepartment();
        boolean done = Boolean.TRUE.equals(transactionTemplate.execute(s -> {
            int rows = complaintMapper.update(null, casOn(c)
                    .in(DataComplaint::getStatus, ComplaintFlow.PENDING, ComplaintFlow.PROCESSING)
                    .set(DataComplaint::getStatus, ComplaintFlow.CLOSED)
                    .set(DataComplaint::getReplyContent, text)
                    .set(DataComplaint::getReplyBy, scope.adminId())
                    .set(DataComplaint::getReplyDepartment, dept)
                    .set(DataComplaint::getReplyTime, now)
                    .set(DataComplaint::getUpdateTime, now));
            if (rows != 1) {
                return false;
            }
            log(id, ComplaintFlow.ACT_REPLY, null, dept, text, true, ComplaintFlow.OP_ADMIN, scope.adminId());
            notificationService.notify(c.getVolunteerId(), VolunteerNotification.TYPE_COMPLAINT_REPLIED,
                    "投诉建议已答复", "您提交的" + ComplaintFlow.typeLabel(c.getComplaintType()) + "（编号 "
                            + c.getComplaintNo() + "）已由" + dept + "答复，点开查看答复内容。",
                    VolunteerNotification.BIZ_COMPLAINT, id);
            smsNotifyService.notifyVolunteer(c.getVolunteerId(), SmsNotifyTemplate.COMPLAINT_REPLIED,
                    SmsNotifyTemplate.COMPLAINT_REPLIED.params(c.getComplaintNo(), abbreviate(text)));
            return true;
        }));
        if (!done) {
            throw conflict(id);
        }
    }

    /** 内部备注（仅后台可见）：已办结的工单也能补记。 */
    public void note(Scope scope, Long id, String content) {
        DataComplaint c = requireVisible(scope, id);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException("请填写备注");
        }
        if (text.codePointCount(0, text.length()) > 2000) {
            throw new BusinessException("备注不超过 2000 字");
        }
        log(id, ComplaintFlow.ACT_NOTE, null, c.getCurrentDepartment(), text, false, ComplaintFlow.OP_ADMIN,
                scope.adminId());
    }

    // ================= 内部 =================

    private static void requireAdmin(Scope scope) {
        if (scope == null || scope.adminId() == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private DataComplaint requireVisible(Scope scope, Long id) {
        requireAdmin(scope);
        DataComplaint c = id == null ? null : complaintMapper.selectById(id);
        if (c == null || !(scope.allDepartments()
                || (scope.department() != null && scope.department().equals(c.getCurrentDepartment())))) {
            throw new BusinessException("工单不存在");
        }
        return c;
    }

    /** 状态迁移的公共条件：这一行、仍在读到时的那个部门（被别人转走了就不算数）。 */
    private static LambdaUpdateWrapper<DataComplaint> casOn(DataComplaint c) {
        return Wrappers.<DataComplaint>lambdaUpdate()
                .eq(DataComplaint::getId, c.getId())
                .eq(DataComplaint::getCurrentDepartment, c.getCurrentDepartment());
    }

    /** CAS 输家的报错：事务已经结束，这里读到的是别人刚提交的结果。 */
    private BusinessException conflict(Long id) {
        DataComplaint now = complaintMapper.selectById(id);
        if (now == null) {
            return new BusinessException("工单不存在");
        }
        return new BusinessException("工单刚刚被处理过（当前：" + ComplaintFlow.statusLabel(now.getStatus()) + "，在"
                + now.getCurrentDepartment() + "），请刷新后再操作");
    }

    private String checkImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        if (images.size() > MAX_IMAGES) {
            throw new BusinessException("图片最多 " + MAX_IMAGES + " 张");
        }
        if (new HashSet<>(images).size() != images.size()) {
            throw new BusinessException("有重复的图片");
        }
        for (String u : images) {
            if (!StringUtils.hasText(u) || u.length() > URL_MAX || !fileStorageService.isOwnUpload(u, IMAGE_DIR)) {
                throw new BusinessException("图片无效，请重新上传");
            }
        }
        return String.join(",", images);
    }

    private String abbreviate(String text) {
        int max = Math.max(1, properties.getSmsReplyMaxChars());
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    private void log(Long complaintId, int action, String from, String to, String content, boolean visible,
                     int operatorType, Long operatorId) {
        DataComplaintLog l = new DataComplaintLog();
        l.setComplaintId(complaintId);
        l.setAction(action);
        l.setFromDepartment(from);
        l.setToDepartment(to);
        l.setContent(content);
        l.setVisible(visible ? 1 : 0);
        l.setOperatorType(operatorType);
        l.setOperatorId(operatorId);
        logMapper.insert(l);
    }

    private List<DataComplaintLog> logs(Long complaintId, boolean onlyVisible) {
        return logMapper.selectList(Wrappers.<DataComplaintLog>lambdaQuery()
                .eq(DataComplaintLog::getComplaintId, complaintId)
                .eq(onlyVisible, DataComplaintLog::getVisible, 1)
                .orderByAsc(DataComplaintLog::getId));
    }

    private static ComplaintVOs.Progress progress(DataComplaintLog l, boolean forAdmin, Map<Long, String> adminNames) {
        ComplaintVOs.Progress p = new ComplaintVOs.Progress();
        p.setAction(l.getAction());
        p.setActionLabel(ComplaintFlow.actionLabel(l.getAction()));
        p.setFromDepartment(l.getFromDepartment());
        p.setToDepartment(l.getToDepartment());
        p.setTime(l.getCreateTime());
        p.setDescription(switch (l.getAction()) {
            case ComplaintFlow.ACT_SUBMIT -> "已提交，由" + l.getToDepartment() + "受理";
            case ComplaintFlow.ACT_ACCEPT -> l.getToDepartment() + "已受理，正在处理";
            case ComplaintFlow.ACT_TRANSFER -> "已转交" + l.getToDepartment() + "处理";
            case ComplaintFlow.ACT_REPLY -> l.getToDepartment() + "已答复";
            default -> "内部备注";
        });
        // 志愿者端只有「答复」这一步带内容：流转理由是部门之间的话
        if (forAdmin || l.getAction() == ComplaintFlow.ACT_REPLY) {
            p.setContent(l.getContent());
        }
        if (forAdmin) {
            p.setVisible(Objects.equals(l.getVisible(), 1));
            if (l.getOperatorType() == ComplaintFlow.OP_ADMIN) {
                p.setOperatorName(adminNames.get(l.getOperatorId()));
            }
        }
        return p;
    }

    private static ComplaintVOs.Complaint toVO(DataComplaint c) {
        ComplaintVOs.Complaint vo = new ComplaintVOs.Complaint();
        vo.setId(c.getId());
        vo.setComplaintNo(c.getComplaintNo());
        vo.setType(c.getComplaintType());
        vo.setTypeLabel(ComplaintFlow.typeLabel(c.getComplaintType()));
        vo.setContent(c.getContent());
        vo.setImages(StringUtils.hasText(c.getImages()) ? new ArrayList<>(Arrays.asList(c.getImages().split(",")))
                : new ArrayList<>());
        vo.setStatus(c.getStatus());
        vo.setStatusLabel(ComplaintFlow.statusLabel(c.getStatus()));
        vo.setCurrentDepartment(c.getCurrentDepartment());
        vo.setTransferCount(c.getTransferCount());
        vo.setAcceptTime(c.getAcceptTime());
        vo.setReplyContent(c.getReplyContent());
        vo.setReplyDepartment(c.getReplyDepartment());
        vo.setReplyTime(c.getReplyTime());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }
}
