package com.hengde.honor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 志愿者提交申诉（Row 41 F「审核之后…有 7 天申诉期」）。
 *
 * @author hengde
 */
@Data
public class AppealSubmitDTO {

    @NotBlank(message = "申诉理由不能为空")
    @Size(max = 1024, message = "申诉理由不超过 1024 字")
    private String reason;
}
