package com.hengde.activity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 活动名单公示（V4 活动补全批，xlsx Row 13「用一个固定模板展现，普通志愿者电话中间打*号，活动负责人则全显示，
 * 活动如果有多个时间段的则按时间段显示」）。
 *
 * @author hengde
 */
@Data
public class RosterVO {

    private Long activityId;
    private Long serialNo;
    private String title;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Schema(description = "组织部确认名单的时间（公示开始）；后台预览时未公示为 null")
    private LocalDateTime rosterPublishTime;

    @Schema(description = "活动负责人（不占名额，不一定报了名）")
    private List<Person> leaders = new ArrayList<>();

    @Schema(description = "按时间段分组的已通过报名")
    private List<Slot> slots = new ArrayList<>();

    /** 一个时间段。 */
    @Data
    public static class Slot {
        private Long slotId;
        private String projectName;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        @Schema(description = "需求人数（0＝不限）")
        private Integer needCount;
        @Schema(description = "管理团队在前，其余按报名先后")
        private List<Person> members = new ArrayList<>();
    }

    /** 名单上的一个人。 */
    @Data
    public static class Person {
        private String name;
        @Schema(description = "电话：活动负责人全显示，其余中间打 *；查看的人是游客时一律打 *")
        private String phone;
        @Schema(description = "是否本活动负责人")
        private boolean leader;
        @Schema(description = "是否管理团队（优先展示）")
        private boolean manager;
        @Schema(description = "是否考试通过的活动临时负责人（排在管理团队之后、其余之前）")
        private boolean tempLeader;
    }
}
