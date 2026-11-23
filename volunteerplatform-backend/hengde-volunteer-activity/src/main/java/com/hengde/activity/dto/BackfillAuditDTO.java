package com.hengde.activity.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部长补录审核（通过/拒绝）入参：可附审核意见/拒绝原因。
 *
 * @author hengde
 */
@Data
public class BackfillAuditDTO {

    /** 审核意见 / 拒绝原因（可空） */
    @Size(max = 512, message = "审核意见不超过 512 字")
    private String reason;
}
