package com.hengde.donate.event;

/**
 * 已成立的结对被取消（{@code donate_pair_record.status: 1 → 2}）后发布的领域事件。
 *
 * <p><b>为什么必须有这一条</b>：结对成立那一刻发了一张盖章的捐赠证书；结对被取消之后那张证书还在，
 * 而它看起来完全正规——有编号、有公章。证书归 honor、依赖方向是 {@code honor → donate}，
 * donate 不能反向调用，所以用与 {@link PairEstablishedEvent} 对称的事件通知 honor 去撤销。</p>
 *
 * <p><b>撤销＝软删</b>（留痕、可恢复），不是物理删除：取消有可能是误操作，
 * 而 V31 定下的软删口径本就支持「恢复原记录、保留原编号与文件」。</p>
 *
 * @param pairRecordId 结对登记 id
 * @param volunteerId  结对人 volunteer.id
 * @param reason       取消原因（写进证书的删除原因，便于事后查）
 * @author hengde
 */
public record PairCancelledEvent(Long pairRecordId, Long volunteerId, String reason) {
}
