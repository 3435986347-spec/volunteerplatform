package com.hengde.system.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统治理入参（V4 系统治理批）。
 *
 * @author hengde
 */
public final class SystemDTOs {

    private SystemDTOs() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "前端上报一次页面访问（Row 62「谁点击了哪个页面」——后端看不见纯前端的路由切换）")
    public static class PageView {
        @Schema(description = "页面名（菜单标题）")
        @NotBlank(message = "请给出页面名")
        @Size(max = 128, message = "页面名不超过 128 字")
        private String page;

        @Schema(description = "前端路由")
        @Size(max = 255, message = "路由不超过 255 字")
        private String path;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "界面水印设置（Row 78「手机尾号、使用者名字、部门」，最高权限可改显示逻辑）")
    public static class WatermarkSave {
        private Boolean enabled;
        private Boolean showName;
        private Boolean showDepartment;
        private Boolean showPhoneTail;
        private Boolean showDate;
        @Min(value = 8, message = "字号 8~40")
        @Max(value = 40, message = "字号 8~40")
        private Integer fontSize;
        @Min(value = 1, message = "透明度 1~50")
        @Max(value = 50, message = "透明度 1~50")
        private Integer opacityPercent;
        @Min(value = -90, message = "角度 -90~90")
        @Max(value = 90, message = "角度 -90~90")
        private Integer rotateDegree;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "后台菜单排序（Row 77，整份覆盖）")
    public static class MenuOrderSave {
        @NotNull(message = "请给出菜单列表")
        private List<Item> items = new ArrayList<>();

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Item {
            @NotBlank(message = "菜单键不能为空")
            @Size(max = 64)
            private String key;
            @Size(max = 64)
            private String name;
            private Integer sort;
            private Boolean visible;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "新建 / 改名文件夹")
    public static class FolderSave {
        @Schema(description = "上级文件夹；空＝根")
        private Long parentId;
        @NotBlank(message = "请填写名称")
        @Size(max = 128, message = "名称不超过 128 字")
        private String name;
        private Integer sort;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "把一个已上传的文件登记进网盘")
    public static class FileSave {
        @NotNull(message = "请选择文件夹")
        private Long folderId;
        @NotBlank(message = "请填写文件名")
        @Size(max = 255, message = "文件名不超过 255 字")
        private String name;
        @Schema(description = "先经 POST /a/files/upload?dir=vault 上传拿到的地址")
        @NotBlank(message = "请先上传文件")
        private String fileUrl;
        private Long fileSize;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "公开到小程序「文件下载」板块 + 开放时间（Row 19）")
    public static class PublishSave {
        @NotNull(message = "请给出是否公开")
        private Boolean published;
        @Schema(description = "开放开始；空＝立即")
        private LocalDateTime publishStart;
        @Schema(description = "开放结束；空＝不设截止")
        private LocalDateTime publishEnd;
        @Schema(description = "允许下载（Row 19「开放 / 关闭下载」）")
        private Boolean allowDownload;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "文件夹授权（Row 71「可以设权限」）")
    public static class GrantSave {
        @NotNull(message = "请选择授权对象类型")
        @Min(value = 1, message = "1 后台账号 / 2 部门")
        @Max(value = 2, message = "1 后台账号 / 2 部门")
        private Integer granteeType;
        private Long adminId;
        @Size(max = 32)
        private String department;
        private Boolean canWrite;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "分享文件")
    public static class ShareSave {
        @Schema(description = "要不要登录后台账号才能打开（默认要）")
        private Boolean requireLogin;
        @Schema(description = "有效小时数；不填走配置默认（168 小时），0 = 不过期")
        @Min(value = 0, message = "有效小时数不能为负")
        @Max(value = 8760, message = "有效小时数最多一年")
        private Integer hours;
    }
}
