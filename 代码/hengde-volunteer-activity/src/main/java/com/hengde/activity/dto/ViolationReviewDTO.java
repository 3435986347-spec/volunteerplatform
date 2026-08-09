package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 违规审核驳回入参。
 *
 * <p>驳回等于否定负责人的现场判断，<b>原因必填</b>——不写理由他既无从改正，也无从申辩。</p>
 *
 * @author hengde
 */
@Data
public class ViolationReviewDTO {

    @NotBlank(message = "驳回原因不能为空")
    @Size(max = 512, message = "驳回原因不超过 512 字")
    private String reason;
}
