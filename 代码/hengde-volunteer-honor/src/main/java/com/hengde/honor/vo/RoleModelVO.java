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

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "图片 URL")
    private String imageUrl;

    @Schema(description = "跳转链接")
    private String linkUrl;

    @Schema(description = "展示排序")
    private Integer sort;

    @Schema(description = "状态 0下架/1上架")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
