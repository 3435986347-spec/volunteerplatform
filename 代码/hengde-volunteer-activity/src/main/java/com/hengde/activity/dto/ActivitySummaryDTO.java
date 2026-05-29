package com.hengde.activity.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动总结入参（负责人或管理端上传）：文字 + 图片 URL（逗号分隔）。
 *
 * @author hengde
 */
@Data
public class ActivitySummaryDTO {

    /** 总结文字 */
    @Size(max = 5000, message = "总结文字不超过 5000 字")
    private String summaryText;

    /** 总结图片 URL（逗号分隔，可空） */
    @Size(max = 2048, message = "图片地址过长")
    private String summaryImages;
}
