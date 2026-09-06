package com.hengde.honor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 志愿者提交申诉（Row 41 F「审核之后…有 7 天申诉期」）。
 *
 * @author hengde
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppealSubmitDTO {

    @NotBlank(message = "申诉理由不能为空")
    @Size(max = 1024, message = "申诉理由不超过 1024 字")
    private String reason;

    /**
     * 申诉凭证图片 URL（V45，可空，最多 6 张）。
     *
     * <p>先经 {@code POST /v/files/appeal-image} 上传拿到 URL，再随申诉一起提交。
     * 数量与长度由 {@code RewardPunishService.appeal} 校验，<b>不靠列宽兜底</b>——
     * 超出列宽会被 MySQL 截断或抛一个指向列名的错，两种都不会告诉用户「最多 6 张」。</p>
     */
    private List<String> imageUrls;
}
