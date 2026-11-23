package com.hengde.activity.event;

import java.util.List;

/**
 * 志愿者往相册传了一批照片、并勾选了「发送到交流平台」（Row 30 默认勾选）。
 *
 * <p><b>由 activity 发、social 监听发帖</b>——activity 不能依赖 social（social 要读活动信息，会成环，V4规划 七）。
 * 事件在上传事务提交之后发布；监听方要自己吞掉异常（同步失败只是少一条帖子，记 ERROR，不补偿）。</p>
 *
 * @param batchId     上传批次
 * @param volunteerId 上传人
 * @param albumTitle  相册标题（帖子正文前缀）
 * @param comment     一起提交的评论
 * @param photoUrls   照片（album/ 目录下本系统上传的）
 * @author hengde
 */
public record AlbumPhotosUploadedEvent(Long batchId, Long volunteerId, String albumTitle, String comment, List<String> photoUrls) {
}
