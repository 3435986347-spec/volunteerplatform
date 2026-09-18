package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.ProxyEnrollDTO;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.RosterService;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.entity.VolunteerSquad;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动补全批压测（{@code @Tag("stress")}）：一场「指定分队」的活动，分队成员与外人混在一起乱序自助报名、同组代报名、取消、
 * 后台通过 / 驳回，同时有人不停地看名单公示、组织部来回确认 / 撤回。
 *
 * <p>断言：外人一条活跃报名都没有；每人至多一组活跃报名；公示上的人与库里「已通过」逐段对得上、负责人以外的电话都打了 *；
 * 除业务拒绝外零异常（死锁、锁超时都算失败）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@Tag("stress")
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class RosterSquadStressTest {

    private static final long ADMIN = 9L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L + 700_000L);

    @Autowired
    private ActivityService activityService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private EnrollmentAdminService enrollmentAdminService;
    @Autowired
    private RosterService rosterService;
    @Autowired
    private ActivityEnrollmentMapper enrollmentMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerSquadMapper squadMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private VolunteerGroupMemberMapper groupMemberMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void squadScopedEnrollmentChurn_withRosterReads() throws Exception {
        VolunteerSquad s = new VolunteerSquad();
        s.setName("压测分队-" + SEQ.incrementAndGet());
        s.setType("学校");
        s.setMemberLimit(0);
        s.setStatus(1);
        squadMapper.insert(s);
        Long squad = s.getId();

        LocalDateTime start = LocalDateTime.now().plusDays(6).withHour(8).withMinute(0).withSecond(0).withNano(0);
        ActivityCreateDTO dto = new ActivityCreateDTO();
        dto.setTitle("名单压测-" + SEQ.incrementAndGet());
        dto.setStartTime(start);
        dto.setEndTime(start.plusHours(10));
        dto.setNeedAudit(1);
        dto.setEnrollScope(1);
        dto.setTargetSquadId(squad);
        List<ActivitySlotDTO> slotDtos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ActivitySlotDTO sd = new ActivitySlotDTO();
            sd.setProjectName("场次" + i);
            sd.setStartTime(start.plusHours(i * 3L));
            sd.setEndTime(start.plusHours(i * 3L + 2));
            sd.setNeedCount(0);
            slotDtos.add(sd);
        }
        dto.setSlots(slotDtos);
        Long aid = activityService.publish(dto, ADMIN);
        List<Long> slots = jdbc.queryForList("SELECT id FROM activity_slot WHERE activity_id = ? ORDER BY start_time", Long.class, aid);
        rosterService.publish(aid, ADMIN);

        List<Long> members = new ArrayList<>();
        List<Long> outsiders = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            members.add(volunteer(squad, i % 7 == 0));
        }
        for (int i = 0; i < 20; i++) {
            outsiders.add(volunteer(null, false));
        }
        // 10 个小组：组长是成员，组里各一名成员一名外人（代报名两种结局都会出现）
        List<List<Long>> groups = new ArrayList<>();
        for (int g = 0; g < 10; g++) {
            groups.add(List.of(members.get(g), members.get(20 + g), outsiders.get(g)));
            group(groups.get(g));
        }
        List<Long> everyone = new ArrayList<>(members);
        everyone.addAll(outsiders);

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        Random rnd = new Random(23L);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            int roll = rnd.nextInt(20);
            Long who = everyone.get(rnd.nextInt(everyone.size()));
            List<Long> grp = groups.get(rnd.nextInt(groups.size()));
            Long pickedSlot = slots.get(rnd.nextInt(slots.size()));
            boolean registered = rnd.nextBoolean();
            boolean withdraw = rnd.nextBoolean();
            tasks.add(() -> {
                String kind = roll < 6 ? "自助报名" : roll < 8 ? "代报名" : roll < 10 ? "取消" : roll < 13 ? "通过"
                        : roll < 14 ? "驳回" : roll < 19 ? "看公示" : "确认或撤回";
                try {
                    switch (kind) {
                        case "自助报名" -> enrollmentService.enroll(aid, List.of(pickedSlot), who);
                        case "代报名" -> {
                            ProxyEnrollDTO p = new ProxyEnrollDTO();
                            p.setVolunteerIds(List.of(grp.get(1 + (roll % 2))));
                            p.setSlotIds(List.of(pickedSlot));
                            enrollmentService.proxyEnroll(aid, p, grp.get(0));
                        }
                        case "取消" -> enrollmentService.cancel(aid, who);
                        case "通过", "驳回" -> {
                            Long id = jdbc.query("SELECT id FROM activity_enrollment WHERE activity_id = ? AND status = 0 "
                                    + "AND is_deleted = 0 ORDER BY RAND() LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null, aid);
                            if (id == null) {
                                throw new BusinessException("没有待审报名");
                            }
                            if (kind.equals("通过")) {
                                enrollmentAdminService.approve(id, ADMIN);
                            } else {
                                enrollmentAdminService.reject(id, "压测驳回", ADMIN);
                            }
                        }
                        case "看公示" -> {
                            // 恰好撞上撤回时是「没有正在公示的名单」，算业务拒绝
                            RosterVO r = rosterService.detailForVolunteer(aid, registered);
                            r.getSlots().forEach(sl -> sl.getMembers().forEach(m -> {
                                if (m.getPhone() != null && !m.getPhone().contains("****") && !(m.isLeader() && registered)) {
                                    failures.add("公示泄露了全号：" + m);
                                }
                            }));
                        }
                        default -> {
                            if (withdraw) {
                                rosterService.withdraw(aid);
                            } else {
                                rosterService.publish(aid, ADMIN);
                            }
                        }
                    }
                    outcomes.computeIfAbsent(kind + "·成功", k -> new AtomicInteger()).incrementAndGet();
                } catch (BusinessException e) {
                    outcomes.computeIfAbsent(kind + "·拒绝·" + e.getMessage().replaceAll("「[^」]*」|\\d+", "…"),
                            k -> new AtomicInteger()).incrementAndGet();
                } catch (Exception e) {
                    failures.add(kind + " → " + e);
                }
            });
        }
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable r : tasks) {
            futures.add(pool.submit(r));
        }
        for (Future<?> f : futures) {
            f.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("==== 名单公示 + 指定分队压测：" + tasks.size() + " 次 / 16 线程 / " + (System.currentTimeMillis() - t0)
                + " ms ====\n  " + new TreeMap<>(outcomes));
        assertTrue(failures.isEmpty(), "除业务拒绝外不应有任何异常：" + failures);

        List<ActivityEnrollment> active = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, aid)
                .in(ActivityEnrollment::getStatus, 0, 1));
        Set<Long> outsiderSet = new HashSet<>(outsiders);
        assertTrue(active.stream().noneMatch(e -> outsiderSet.contains(e.getVolunteerId())), "外人不该有活跃报名");
        Map<Long, Long> perPerson = new TreeMap<>();
        active.forEach(e -> perPerson.merge(e.getVolunteerId(), 1L, Long::sum));
        assertTrue(perPerson.values().stream().allMatch(n -> n == 1), "每人至多一条活跃报名（每次只报一个场次）：" + perPerson);

        RosterVO preview = rosterService.previewForAdmin(aid);
        for (RosterVO.Slot sl : preview.getSlots()) {
            long approved = active.stream().filter(e -> e.getStatus() == 1 && e.getSlotId().equals(sl.getSlotId())).count();
            assertEquals(approved, sl.getMembers().size(), "场次 " + sl.getProjectName() + " 公示人数与已通过对得上");
            for (int i = 1; i < sl.getMembers().size(); i++) {
                assertTrue(sl.getMembers().get(i - 1).isManager() || !sl.getMembers().get(i).isManager(), "管理团队排在前面");
            }
        }
    }

    private Long volunteer(Long squadId, boolean manager) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:roster-stress:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("压测志愿者" + SEQ.get());
        v.setGender(Gender.FEMALE);
        v.setBirthday(LocalDate.of(2001, 3, 3));
        v.setStatus(0);
        v.setManagerFlag(manager ? 1 : 0);
        v.setSquadId(squadId);
        v.setPhone(cryptoUtil.encrypt("139" + String.format("%08d", SEQ.get() % 100_000_000L)));
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void group(List<Long> people) {
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G_" + System.nanoTime());
        g.setName("压测小组_" + SEQ.incrementAndGet());
        g.setLeaderId(people.get(0));
        g.setStatus(1);
        groupMapper.insert(g);
        for (int i = 0; i < people.size(); i++) {
            VolunteerGroupMember m = new VolunteerGroupMember();
            m.setGroupId(g.getId());
            m.setVolunteerId(people.get(i));
            m.setRole(i == 0 ? 1 : 0);
            m.setStatus(1);
            m.setApplyTime(LocalDateTime.now());
            m.setAuditTime(LocalDateTime.now());
            groupMemberMapper.insert(m);
        }
    }
}
