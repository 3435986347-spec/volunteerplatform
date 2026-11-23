package com.hengde.donate.event;

/**
 * 一次捐赠不再够格持有证书（当前只有众筹捐款被退款这一种）——honor 撤销那张证书（软删，留原因可恢复）。
 *
 * <p>理由与结对取消撤证相同：钱退回去了，留下的是一张为没有发生的捐款盖章的凭据。</p>
 *
 * @param bizRef      来源键
 * @param volunteerId 捐赠人（只用于日志）
 * @param reason      撤销原因
 */
public record DonationCertRevokedEvent(String bizRef, Long volunteerId, String reason) {
}
