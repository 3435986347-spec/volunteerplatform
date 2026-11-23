package com.hengde.activity.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 活动发布审核-驳回入参（V19）。原因可选、限长。
 *
 * @author hengde
 */
@Getter
@Setter
public class PublishRejectDTO {

    /** 驳回原因（可选，≤512） */
    @Size(max = 512, message = "驳回原因不超过 512 字")
    private String reason;
}
