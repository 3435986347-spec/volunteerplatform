package com.hengde.honor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 榜样的新增/修改入参。
 *
 * @author hengde
 */
@Data
@Schema(description = "榜样")
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleModelSaveDTO {

    @Schema(description = "标题")
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题不超过 128 字")
    private String title;

    @Schema(description = "类型 1个人/2团队；留空按 1 个人")
    private Integer modelType;

    @Schema(description = "副标题")
    @Size(max = 256, message = "副标题不超过 256 字")
    private String subtitle;

    @Schema(description = "简介（小程序列表卡片正文）")
    @Size(max = 1024, message = "简介不超过 1024 字")
    private String summary;

    @Schema(description = "图片 URL")
    @Size(max = 512, message = "图片 URL 过长")
    private String imageUrl;

    @Schema(description = "跳转类型 0不跳转/1小程序页面/2网页WebView/3外部链接仅复制；留空按 0 不跳转")
    private Integer linkType;

    @Schema(description = "跳转链接（推文等）")
    @Size(max = 512, message = "跳转链接过长")
    private String linkUrl;

    @Schema(description = "展示排序，小的在前")
    private Integer sort;
}
