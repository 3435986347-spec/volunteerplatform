package com.hengde.activity;

import com.hengde.activity.album.dao.ActivityAlbumMapper;
import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.entity.ActivityAlbum;
import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.album.vo.AlbumVOs;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.event.AlbumPhotosUploadedEvent;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.service.EnrollmentService;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.constant.Gender;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动相册用例的公共造数（活动、报名、志愿者、上传入参）。
 *
 * @author hengde
 */
abstract class AlbumTestSupport {


    static final long ADMIN = 11L;
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @Autowired
    AlbumService albumService;
    @Autowired
    ActivityService activityService;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    ActivityLeaderService leaderService;
    @Autowired
    PointService pointService;
    @Autowired
    ActivityAlbumMapper albumMapper;
    @Autowired
    VolunteerMapper volunteerMapper;
    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------------

    ActivityCreateDTO base() {
        return base(3);
    }

    /** 不同的活动错开日期——同一个志愿者报两场同一时段的活动会被「时间冲突」挡掉。 */
    ActivityCreateDTO base(int daysAhead) {
        LocalDateTime start = LocalDateTime.now().plusDays(daysAhead).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ActivitySlotDTO s = new ActivitySlotDTO();
        s.setProjectName("相册场");
        s.setStartTime(start);
        s.setEndTime(start.plusHours(2));
        s.setNeedCount(0);
        ActivityCreateDTO t = new ActivityCreateDTO();
        t.setTitle("相册活动_" + SEQ.incrementAndGet());
        t.setStartTime(start);
        t.setEndTime(start.plusHours(2));
        t.setSlots(List.of(s));
        return t;
    }

    Long firstSlot(Long aid) {
        return jdbc.queryForObject("SELECT id FROM activity_slot WHERE activity_id = ? ORDER BY id LIMIT 1", Long.class, aid);
    }

    Long enrolledVolunteer(Long aid) {
        Long v = volunteer(true, 0);
        enrollmentService.enroll(aid, List.of(firstSlot(aid)), v);
        return v;
    }

    Long volunteer(boolean registered, int managerFlag) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:album:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("相册志愿者" + SEQ.get());
        v.setGender(Gender.FEMALE);
        v.setBirthday(LocalDate.of(2002, 2, 2));
        v.setStatus(0);
        v.setManagerFlag(managerFlag);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    static AlbumDTOs.Upload upload(int n) {
        AlbumDTOs.Upload u = new AlbumDTOs.Upload();
        u.setPhotoUrls(IntStream.range(0, n).mapToObj(i -> "[oss-disabled]/album/" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg").toList());
        return u;
    }

    static AlbumDTOs.Upload noSync(int n) {
        AlbumDTOs.Upload u = upload(n);
        u.setSyncSocial(false);
        return u;
    }

    static AlbumDTOs.Create create(Long activityId, String title) {
        AlbumDTOs.Create c = new AlbumDTOs.Create();
        c.setActivityId(activityId);
        c.setTitle(title);
        return c;
    }

    static AlbumDTOs.Rule rule(boolean enabled, int photos, int points, int max) {
        AlbumDTOs.Rule r = new AlbumDTOs.Rule();
        r.setEnabled(enabled);
        r.setPhotosPerUnit(photos);
        r.setPointsPerUnit(points);
        r.setMaxPointsPerAlbum(max);
        return r;
    }

    static PageQuery page(int size) {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(size);
        return q;
    }

    static void assertMessage(String fragment, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望提示含「" + fragment + "」，实际：" + e.getMessage());
    }
}
