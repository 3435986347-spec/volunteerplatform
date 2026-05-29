package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 负责人对志愿者的评价入参。
 *
 * @author hengde
 */
@Data
public class LeaderEvaluationDTO {

    /** 评价内容 */
    @NotBlank(message = "评价内容不能为空")
    @Size(max = 512, message = "评价内容不超过 512 字")
    private String evaluation;
}
