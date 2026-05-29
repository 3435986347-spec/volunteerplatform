package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报名审核拒绝入参。
 *
 * @author hengde
 */
@Data
public class RejectEnrollmentDTO {

    /** 拒绝原因 */
    @NotBlank(message = "请填写拒绝原因")
    @Size(max = 255, message = "拒绝原因不能超过 255 字")
    private String reason;
}
