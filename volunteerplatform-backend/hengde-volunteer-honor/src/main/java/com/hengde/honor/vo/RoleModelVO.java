package com.hengde.honor.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 榜样。
 *
 * @author hengde
 */
@Data
@Schema(description = "榜样")
public class RoleModelVO {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "类型 1个人/2团队")
    private Integer modelType;

    @Schema(description = "类型中文名")
    private String modelTypeLabel;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "简介")
    private String summary;

    @Schema(description = "图片 URL")
    private String imageUrl;

    @Schema(description = "跳转类型码 0不跳转/1小程序页面/2网页WebView/3外部链接仅复制")
    private Integer linkType;

    @Schema(description = "跳转类型的稳定英文名 NONE/PAGE/WEB/EXTERNAL，客户端直接 switch 它，"
            + "不必自己维护一份「1 是什么」的映射")
    private String linkTypeName;

    @Schema(description = "跳转链接")
    private String linkUrl;

    @Schema(description = "首次上架时间；未上架过为空")
    private LocalDateTime publishTime;

    @Schema(description = "展示排序")
    private Integer sort;

    @Schema(description = "状态 0下架/1上架")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
