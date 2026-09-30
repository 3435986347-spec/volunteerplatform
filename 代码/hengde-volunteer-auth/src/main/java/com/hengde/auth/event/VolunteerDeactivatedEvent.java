package com.hengde.auth.event;

/**
 * 一个志愿者的注销刚刚落定（status=2、手机号已释放）。
 *
 * <p>由 auth 的 {@code VolunteerDeactivationService} 在落定那条 UPDATE 成功之后发布。
 * 注销＝退出协会（协会 2026-09-16），他在别的域占着的位置要让出来——组织架构里的职位、小组里的成员名额；
 * 那些表在 organization，auth 不能反向依赖它，所以只发事件、由 organization 订阅收拾。</p>
 *
 * <p>⚠️ 事件是进程内、不持久的：订阅方必须另有定时兜底（organization 的 {@code DeactivationCleanupJob}），
 * 否则一次失败就会变成一条没人读的日志（电子证书那批记过同一课）。</p>
 *
 * @param volunteerId 刚落定注销的志愿者
 * @author hengde
 */
public record VolunteerDeactivatedEvent(Long volunteerId) {
}
