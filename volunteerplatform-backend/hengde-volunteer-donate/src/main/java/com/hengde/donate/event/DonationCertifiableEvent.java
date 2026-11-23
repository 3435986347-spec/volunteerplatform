package com.hengde.donate.event;

/**
 * 一次捐赠够格出证了（协会 2026-10-16 答复五 乙-2）：微心愿已实现、捐书 / 众筹捐物核对出合格物资、众筹捐款到账。
 *
 * <p>与 {@link PairEstablishedEvent} 同形：donate 发、honor 订阅出证（依赖方向 honor → donate，donate 不能反向调用）。
 * 事件是进程内、不持久的——丢了用后台人工补发 {@code POST /a/honor/certificates/donations/{kind}/{id}} 救济（幂等）。</p>
 *
 * @param bizRef      {@link com.hengde.donate.constant.DonateCertRefs} 定义的来源键
 * @param volunteerId 捐赠人（只用于日志）
 */
public record DonationCertifiableEvent(String bizRef, Long volunteerId) {
}
