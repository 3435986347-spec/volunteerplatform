package com.hengde.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统治理出参（V4 系统治理批）。
 *
 * @author hengde
 */
public final class SystemVOs {

    private SystemVOs() {
    }

    /** 一条操作日志 / 页面访问。 */
    @Data
    public static class OperationLog {
        private Long id;
        private Integer logType;
        private String logTypeLabel;
        private Integer actorType;
        private String actorTypeLabel;
        private Long actorId;
        private String actorName;
        private String department;
        private String action;
        private String method;
        private String uri;
        private String query;
        private String ip;
        private boolean success;
        private String errorMsg;
        private Integer costMs;
        private LocalDateTime createTime;
    }

    /** 界面水印（Row 78）：开关 + 显示哪几样 + 样式；`text` 是给当前这个人算好的那一行。 */
    @Data
    public static class Watermark {
        private boolean enabled;
        @Schema(description = "显示姓名")
        private boolean showName;
        @Schema(description = "显示部门")
        private boolean showDepartment;
        @Schema(description = "显示手机尾号（后 4 位）")
        private boolean showPhoneTail;
        @Schema(description = "显示日期")
        private boolean showDate;
        private Integer fontSize;
        private Integer opacityPercent;
        private Integer rotateDegree;
        @Schema(description = "给当前登录账号算好的水印文字（前端直接铺）")
        private String text;
    }

    /** 后台菜单排序（Row 77）：整份覆盖的一张列表。 */
    @Data
    public static class MenuOrder {
        private List<MenuItem> items = new ArrayList<>();
    }

    @Data
    public static class MenuItem {
        @Schema(description = "菜单键（与前端 shell.js 的菜单 key 一致）")
        private String key;
        private String name;
        private Integer sort;
        @Schema(description = "false = 在侧边栏里藏起来（权限仍以后端为准）")
        private boolean visible = true;
    }

    /** 编号段（Row 75）。 */
    @Data
    public static class Serial {
        private String segment;
        private String name;
        private Long currentNo;
    }

    /** 网盘文件夹。 */
    @Data
    public static class Folder {
        private Long id;
        private Long parentId;
        private String name;
        private Integer sort;
        private Long fileCount;
        @Schema(description = "当前账号在这个文件夹里能不能写")
        private boolean canWrite;
        private List<Folder> children = new ArrayList<>();
    }

    /** 网盘文件（后台看）。 */
    @Data
    public static class VaultFile {
        private Long id;
        private Long folderId;
        @Schema(description = "十位编号（Row 75）")
        private String serialNo;
        private String name;
        private String fileUrl;
        private String fileExt;
        private Long fileSize;
        private boolean published;
        private LocalDateTime publishStart;
        private LocalDateTime publishEnd;
        private boolean allowDownload;
        @Schema(description = "此刻对志愿者开不开放（按时间现算）")
        private boolean openNow;
        private LocalDateTime createTime;
    }

    /** 志愿者端看到的「内置文件」（Row 19）。 */
    @Data
    public static class OpenFile {
        private Long id;
        private String serialNo;
        private String name;
        private String fileExt;
        private Long fileSize;
        @Schema(description = "允许下载时才给地址；关掉下载时为空")
        private String fileUrl;
        private boolean allowDownload;
        private LocalDateTime publishStart;
        private LocalDateTime publishEnd;
    }

    /** 文件夹授权。 */
    @Data
    public static class FolderGrant {
        private Long id;
        private Long folderId;
        private Integer granteeType;
        private String granteeLabel;
        private Long adminId;
        private String department;
        private boolean canWrite;
        private LocalDateTime createTime;
    }

    /** 分享链接。 */
    @Data
    public static class FileShare {
        private Long id;
        private Long fileId;
        private String fileName;
        private String token;
        private boolean requireLogin;
        private LocalDateTime expireTime;
        private Integer downloadCount;
        private LocalDateTime createTime;
        @Schema(description = "此刻还能不能打开")
        private boolean usable;
    }

    /** 打开分享链接看到的东西。 */
    @Data
    public static class SharedFile {
        private String name;
        private String fileExt;
        private Long fileSize;
        private String fileUrl;
    }
}
