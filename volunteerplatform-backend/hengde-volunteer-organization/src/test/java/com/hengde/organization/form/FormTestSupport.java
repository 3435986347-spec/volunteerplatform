package com.hengde.organization.form;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.support.QuestionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 问卷用例的共用造数。
 *
 * @author hengde
 */
final class FormTestSupport {

    static final long ADMIN = 7701L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    private FormTestSupport() {
    }

    static long next() {
        return SEQ.incrementAndGet();
    }

    static Long volunteer(VolunteerMapper mapper, boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:form:" + System.nanoTime() + ":" + next());
        v.setRealName(registered ? "问卷填写人" + next() : null);
        v.setStatus(0);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        mapper.insert(v);
        return v.getId();
    }

    /** 七种题型各一道（文件题选答，其余必答）。 */
    static FormDTOs.Save allTypes(String title) {
        FormDTOs.Save d = new FormDTOs.Save();
        d.setTitle(title);
        d.setDescription("用例问卷");
        List<FormDTOs.QuestionSave> qs = new ArrayList<>();
        qs.add(q(QuestionType.SINGLE, "你来自哪里", List.of("雷城", "客路", "乌石")));
        FormDTOs.QuestionSave multi = q(QuestionType.MULTI, "擅长什么", List.of("摄影", "写作", "组织", "急救"));
        multi.setMinSelect(1);
        multi.setMaxSelect(3);
        qs.add(multi);
        qs.add(q(QuestionType.JUDGE, "是否参加过志愿活动", null));
        FormDTOs.QuestionSave fill = q(QuestionType.FILL, "学校", null);
        fill.setMaxLength(20);
        qs.add(fill);
        qs.add(q(QuestionType.TEXT, "自我介绍", null));
        FormDTOs.QuestionSave file = q(QuestionType.FILE, "简历附件", null);
        file.setRequired(false);
        file.setMaxFiles(2);
        qs.add(file);
        FormDTOs.QuestionSave date = q(QuestionType.DATE, "可以开始的日期", null);
        date.setMinDate(LocalDate.of(2026, 1, 1));
        date.setMaxDate(LocalDate.of(2027, 12, 31));
        qs.add(date);
        d.setQuestions(qs);
        return d;
    }

    static FormDTOs.QuestionSave q(int type, String title, List<String> options) {
        FormDTOs.QuestionSave q = new FormDTOs.QuestionSave();
        q.setType(type);
        q.setTitle(title);
        q.setOptions(options);
        return q;
    }

    static FormDTOs.Answer a(Long questionId, Object value) {
        FormDTOs.Answer a = new FormDTOs.Answer();
        a.setQuestionId(questionId);
        a.setValue(value);
        return a;
    }

    static FormDTOs.Submit submit(List<FormDTOs.Answer> answers) {
        FormDTOs.Submit s = new FormDTOs.Submit();
        s.setAnswers(answers);
        return s;
    }

    /** 本系统在 OSS 未启用时 upload 返回的占位 URL 形状（与 AliyunOssFileStorageService 一致）。 */
    static String ownFileUrl(String ext) {
        String hex = String.format("%032x", next());
        return "[oss-disabled]/form/20260917/" + hex + ext;
    }
}
