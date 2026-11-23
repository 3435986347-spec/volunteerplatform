package com.hengde.activity.event;

/**
 * 秘书部确认某条考勤（{@code secretary_status: 0 → 1}）后发布的领域事件。
 *
 * <p><b>为什么用事件而不是直接调用</b>：证书归 {@code honor} 模块，而模块依赖方向是
 * {@code honor → activity}。若在 {@code ServiceRecordService} 里直接调证书服务就成了反向依赖、
 * 形成循环。事件类定义在 {@code activity}（发布方），{@code honor} 作为下游订阅，方向不变。</p>
 *
 * <p><b>监听方必须用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}</b>：
 * 确认动作本身是一个事务，若在提交前就去建证书，确认回滚了证书却留下了。</p>
 *
 * @param attendanceId 考勤行 id
 * @param activityId   活动 id
 * @param slotId       场次 id（V30 起考勤按场次，证书也按场次发）
 * @param volunteerId  志愿者 id
 * @author hengde
 */
public record AttendanceConfirmedEvent(Long attendanceId, Long activityId, Long slotId, Long volunteerId) {
}
