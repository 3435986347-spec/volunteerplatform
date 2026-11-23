package com.hengde.organization.biz.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 驳回报名管理团队申请入参。
 *
 * @author hengde
 */
@Getter
@Setter
public class RejectManagerApplicationDTO {
    @Size(max = 512, message = "驳回原因不超过512字")
    private String reason;
}
