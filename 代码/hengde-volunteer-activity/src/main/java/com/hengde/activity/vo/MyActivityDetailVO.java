package com.hengde.activity.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的活动」详情：列表摘要 + 定位/签到二维码数据 + 负责人 + 确认到家 + 双向评价回显。
 *
 * <p>签到二维码数据 {@link #checkInQrContent} 仅为占位字符串（前端据此渲染/扫码后调 GPS 签到端点），
 * 服务端不做签名 token 校验——真正的门槛是 GPS 距离 + 时间窗 + 报名校验。后续可升级为签名 token。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MyActivityDetailVO extends MyActivityVO {

    private String location;
    /** 活动签到坐标与半径（供前端 GPS 自助签到） */
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer checkInRadiusM;
    /** 负责人列表 */
    private List<ActivityLeaderVO> leaders;
    /** 签到二维码数据（占位：encode activityId） */
    private String checkInQrContent;
    /** 确认到家时间 */
    private LocalDateTime confirmHomeTime;
    /** 是否超时确认到家（结束 1h 后才确认；仅记录，不影响其他） */
    private Boolean confirmHomeOverdue;
    /** 我对活动评分（回显） */
    private Integer myActivityScore;
    /** 我对负责人评分（回显） */
    private Integer myLeaderScore;
    /** 我的评价留言（回显） */
    private String myComment;
    /** 负责人对我的评价（回显） */
    private String leaderEvaluationOfMe;

    /** 「紧急上报 / 联系负责人」预设电话（前端 tel: 拨号；后台配置，未配为 null） */
    private String emergencyPhone;
}
