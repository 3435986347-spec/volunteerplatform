package com.hengde.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改一个话题的订阅。
 *
 * @author hengde
 */
@Data
@Schema(description = "订阅设置")
public class NotifyPreferenceDTO {

    @NotBlank(message = "请指定提醒话题")
    @Schema(description = "话题名，取自 GET /v/user/notify-preferences 的 topic")
    private String topic;

    @NotNull(message = "请指定打开还是关闭")
    private Boolean smsEnabled;
}
