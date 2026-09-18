package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件网盘的文件（V83，Row 71 / Row 19）。
 *
 * <p><b>「现在开不开放」按时间现算</b>（{@code published} + 起止时间），不靠定时任务改状态位——
 * 漏跑一次就会让本该开放的文件一直关着，而这条纪律本项目已经反复用过（名单公示、处置到期）。</p>
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_file")
public class SysFile {
    private Long id;
    private Long folderId;
    private String serialNo;
    private String name;
    private String fileUrl;
    private String fileExt;
    private Long fileSize;
    private Long uploadBy;
    private Integer published;
    private LocalDateTime publishStart;
    private LocalDateTime publishEnd;
    private Integer allowDownload;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;

    /** 这一刻对志愿者开不开放。 */
    public boolean openAt(LocalDateTime now) {
        return Integer.valueOf(1).equals(published)
                && (publishStart == null || !publishStart.isAfter(now))
                && (publishEnd == null || publishEnd.isAfter(now));
    }
}
