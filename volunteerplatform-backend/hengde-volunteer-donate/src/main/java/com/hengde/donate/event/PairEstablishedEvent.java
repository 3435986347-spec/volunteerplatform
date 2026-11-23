package com.hengde.donate.event;

/**
 * 结对成立（{@code donate_pair_record.status: 0 → 1}）后发布的领域事件。
 *
 * <p><b>为什么用事件而不是直接调用</b>：捐赠证书归 {@code honor}，而模块依赖方向自 V3 微心愿批起
 * 是 {@code honor → donate}（微心愿排行的数据源在 donate）。若在 donate 里直接调证书服务就成了反向依赖、
 * 形成循环。事件类定义在 donate（发布方），honor 作为下游订阅，方向不变——
 * 与 activity 的 {@code AttendanceConfirmedEvent} 完全同形。</p>
 *
 * <p><b>订阅方必须用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}</b>：
 * 成立动作本身是一个事务，若在提交前就去建证书，成立回滚了证书却留下了。</p>
 *
 * @param pairRecordId 结对登记 id（证书的业务来源键 {@code pair:{id}} 由它组装）
 * @param projectId    结对项目 id
 * @param volunteerId  结对人 volunteer.id
 * @author hengde
 */
public record PairEstablishedEvent(Long pairRecordId, Long projectId, Long volunteerId) {
}
