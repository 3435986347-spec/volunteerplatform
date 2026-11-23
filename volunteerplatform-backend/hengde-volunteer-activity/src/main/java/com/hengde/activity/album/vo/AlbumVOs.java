package com.hengde.activity.album.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 活动相册出参（V4 活动相册批）。
 *
 * @author hengde
 */
public final class AlbumVOs {

    private AlbumVOs() {
    }

    @Data
    public static class Album {
        private Long id;
        private Long activityId;
        private String title;
        @Schema(description = "封面：最新一张照片")
        private String coverUrl;
        private long photoCount;
        private LocalDateTime lastUploadTime;
        private LocalDateTime createTime;
        @Schema(description = "我能不能往这个相册传（志愿者端）")
        private Boolean uploadable;
    }

    @Data
    public static class Photo {
        private Long id;
        private Long batchId;
        private String url;
        private String uploaderName;
        private LocalDateTime createTime;
    }

    /** 上传记录：谁传的、传了多少张（Row 11）。 */
    @Data
    public static class Batch {
        private Long id;
        private Long albumId;
        private String albumTitle;
        private Integer uploaderType;
        private Long uploaderId;
        private String uploaderName;
        private Integer photoCount;
        @Schema(description = "这一批现在还没删的张数")
        private long remainingCount;
        private String comment;
        private Long socialPostId;
        @Schema(description = "0 待审核 / 1 已通过 / 2 已驳回 / 9 不参与积分")
        private Integer pointsStatus;
        private Integer awardedPoints;
        private String rejectReason;
        private LocalDateTime reviewedTime;
        private LocalDateTime createTime;
        private List<String> previewUrls = new ArrayList<>();
    }

    @Data
    public static class Rule {
        private boolean enabled;
        private int photosPerUnit;
        private int pointsPerUnit;
        private int maxPointsPerAlbum;
    }
}
