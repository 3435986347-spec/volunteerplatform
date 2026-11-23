package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.HomeConfirmationVO;
import com.hengde.auth.service.VolunteerQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动结束后的「到家」名单（V4 系统治理批，Row 63「活动结束，志愿者到家定位：详细地址最高权限才可查看，其他权限均显示已到家」）。
 *
 * <p><b>可见性做在字段上，不做在前端</b>：没有 {@code activity:home-address} 的账号拿到的响应里根本没有地址与坐标。
 * 「后端照发、前端隐藏」这种做法在这里等于没做——打开开发者工具就看得到，而这一列正是志愿者的家庭住址。</p>
 *
 * <p>名单列的是<b>签过到的人</b>：没到场的人谈不上「有没有到家」，把他们列进来只会让负责人以为有人失联。</p>
 *
 * @author hengde
 */
@Service
public class HomeConfirmationService {

    private ActivityAttendanceMapper attendanceMapper;
    private ActivitySlotMapper slotMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 一场活动的到家名单。
     *
     * @param withAddress 调用方是否持 {@code activity:home-address}（控制器判定）——false 时不下发地址与坐标
     */
    public List<HomeConfirmationVO> list(Long activityId, boolean withAddress) {
        List<ActivityAttendance> rows = attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .isNotNull(ActivityAttendance::getCheckInTime)
                .orderByAsc(ActivityAttendance::getSlotId)
                .orderByAsc(ActivityAttendance::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> volunteerIds = new HashSet<>();
        Set<Long> slotIds = new HashSet<>();
        rows.forEach(r -> {
            volunteerIds.add(r.getVolunteerId());
            if (r.getSlotId() != null) {
                slotIds.add(r.getSlotId());
            }
        });
        Map<Long, String> names = volunteerQueryService.listNamesByIds(volunteerIds);
        Map<Long, String> slotNames = new HashMap<>();
        if (!slotIds.isEmpty()) {
            for (ActivitySlot slot : slotMapper.selectBatchIds(slotIds)) {
                slotNames.put(slot.getId(), slot.getProjectName());
            }
        }
        List<HomeConfirmationVO> out = new ArrayList<>(rows.size());
        for (ActivityAttendance r : rows) {
            HomeConfirmationVO vo = new HomeConfirmationVO();
            vo.setVolunteerId(r.getVolunteerId());
            vo.setVolunteerName(names.get(r.getVolunteerId()));
            vo.setSlotId(r.getSlotId());
            vo.setSlotName(slotNames.get(r.getSlotId()));
            vo.setConfirmed(r.getConfirmHomeTime() != null);
            vo.setConfirmTime(r.getConfirmHomeTime());
            if (withAddress) {
                vo.setAddress(r.getConfirmHomeAddress());
                vo.setLat(r.getConfirmHomeLat());
                vo.setLng(r.getConfirmHomeLng());
            }
            out.add(vo);
        }
        return out;
    }
}
