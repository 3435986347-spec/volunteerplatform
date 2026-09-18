package com.hengde.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 设置个人中心内容（我的保险 / 联系客服）。
 *
 * @author hengde
 */
@Data
@Schema(description = "个人中心内容")
public class CenterContentSaveDTO {

    @Size(max = 128, message = "标题不超过 128 字")
    private String title;

    @Schema(description = "正文（客服电话写在这里）")
    @Size(max = 5000, message = "正文不超过 5000 字")
    private String body;

    @Schema(description = "图片 URL（最多 6 张，先经 POST /a/files/upload?dir=center 上传）")
    @Size(max = 6, message = "图片最多 6 张")
    private List<String> images;
}
