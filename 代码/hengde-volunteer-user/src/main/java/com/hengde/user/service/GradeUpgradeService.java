package com.hengde.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Grade;
import com.hengde.common.constant.UserStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 年级每年 9 月升一级（Row 25「年级每年9月份增加一级；六年级、九年级、高三、大三、大四、大五结束后的9月份会提示他们修改学校和年级；
 * 选择已毕业的，则不需要后续提示」，V4 个人中心补全批）。
 *
 * <p><b>按学年幂等</b>：每个人记着「年级已对应到哪个学年」（{@code grade_upgrade_year}，9 月 1 日为界），
 * 只处理小于当前学年的行，并在同一条 UPDATE 里把它置为当前学年——再跑一遍什么也不改，
 * 某年 9 月 1 日服务不在线，之后哪天跑都补得上。不靠「每年只在 9 月 1 日跑一次」。</p>
 *
 * <p>三步，每步一条带条件的 UPDATE（互不依赖，中途失败重跑即可）：</p>
 * <ol>
 *   <li><b>补基线</b>：有年级却没记学年的（回填时拿不到时间的边角行）置为当前学年、不升级——宁可漏升一次，不能多升；</li>
 *   <li><b>升一级</b>：非分界年级、没挂提示的 +1；</li>
 *   <li><b>挂提示</b>：分界年级（六年级 / 九年级 / 高三 / 大三 / 大四 / 大五）不替他升，挂上「请修改学校和年级」——
 *       读完这一级多半换学校或毕业，自动升到下一级是在替他瞎填。本人改过年级即清除（{@code MyProfileService}）。</li>
 * </ol>
 *
 * <p>「毕业」不升也不提示；已注销的账号、游客不动。挂着提示没改的人，年级停在原地、学年照常前移，不会被逐年重复处理。</p>
 *
 * @author hengde
 */
@Service
public class GradeUpgradeService {

    /** 一次跑的结果。 */
    public record Result(int schoolYear, int baselined, int upgraded, int prompted) {
    }

    private static final List<Integer> STAGE_END = Arrays.stream(Grade.values()).filter(Grade::isStageEnd)
            .map(Grade::getCode).toList();
    private static final List<Integer> UPGRADABLE = Arrays.stream(Grade.values())
            .filter(g -> g != Grade.GRADUATED && !g.isStageEnd()).map(Grade::getCode).toList();

    private VolunteerMapper volunteerMapper;

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    public Result run(LocalDate today) {
        int year = Grade.schoolYearOf(today);
        LocalDateTime now = LocalDateTime.now();
        int baselined = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .isNotNull(Volunteer::getGrade)
                .isNull(Volunteer::getGradeUpgradeYear)
                .set(Volunteer::getGradeUpgradeYear, year)
                .set(Volunteer::getUpdateTime, now));
        int upgraded = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .in(Volunteer::getGrade, UPGRADABLE)
                .lt(Volunteer::getGradeUpgradeYear, year)
                .eq(Volunteer::getGradePromptPending, 0)
                .isNotNull(Volunteer::getRegisterTime)
                .ne(Volunteer::getStatus, UserStatus.DELETED)
                .setSql("grade = grade + 1")
                .set(Volunteer::getGradeUpgradeYear, year)
                .set(Volunteer::getUpdateTime, now));
        int prompted = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .in(Volunteer::getGrade, STAGE_END)
                .lt(Volunteer::getGradeUpgradeYear, year)
                .eq(Volunteer::getGradePromptPending, 0)
                .isNotNull(Volunteer::getRegisterTime)
                .ne(Volunteer::getStatus, UserStatus.DELETED)
                .set(Volunteer::getGradePromptPending, 1)
                .set(Volunteer::getGradeUpgradeYear, year)
                .set(Volunteer::getUpdateTime, now));
        // 挂着提示、一直没改的人：学年照常前移，免得每年都被当成「待处理」扫一遍
        volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .lt(Volunteer::getGradeUpgradeYear, year)
                .eq(Volunteer::getGradePromptPending, 1)
                .set(Volunteer::getGradeUpgradeYear, year));
        return new Result(year, baselined, upgraded, prompted);
    }
}
