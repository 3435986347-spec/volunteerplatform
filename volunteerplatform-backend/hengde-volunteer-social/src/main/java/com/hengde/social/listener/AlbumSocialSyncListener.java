package com.hengde.social.listener;

import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.event.AlbumPhotosUploadedEvent;
import com.hengde.common.exception.BusinessException;
import com.hengde.social.service.SocialPostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 相册上传「同步发送到交流平台」（Row 30「默认勾选发送到交流平台」，V4规划 七：social → activity 监听事件，不让 activity 依赖 social）。
 *
 * <p><b>失败只是少一条帖子</b>：被禁止发帖、没实名等业务拒绝记 WARN，其余异常记 ERROR；都不外抛（外抛会让已经提交的上传接口报错），
 * 也不做补偿任务（V4规划 七的口径）。事件在上传事务之外发布，故需 {@code fallbackExecution = true}，发帖自己开事务。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class AlbumSocialSyncListener {

    private SocialPostService postService;
    private AlbumService albumService;

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
    }

    @Autowired
    public void setAlbumService(AlbumService albumService) {
        this.albumService = albumService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlbumPhotosUploaded(AlbumPhotosUploadedEvent event) {
        try {
            Long postId = postService.publishFromAlbum(event.volunteerId(), event.albumTitle(), event.comment(), event.photoUrls());
            albumService.markSocialPost(event.batchId(), postId);
        } catch (BusinessException e) {
            log.warn("[ALBUM→SOCIAL] 相册批次 {} 没有同步到交流平台：{}", event.batchId(), e.getMessage());
        } catch (Exception e) {
            log.error("[ALBUM→SOCIAL] 相册批次 {} 同步交流平台失败", event.batchId(), e);
        }
    }
}
