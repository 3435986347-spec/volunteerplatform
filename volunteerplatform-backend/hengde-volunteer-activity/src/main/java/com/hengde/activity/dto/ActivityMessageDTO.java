package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表活动留言入参。
 *
 * @author hengde
 */
@Data
public class ActivityMessageDTO {

    /** 留言内容 */
    @NotBlank(message = "留言内容不能为空")
    @Size(max = 500, message = "留言内容不超过 500 字")
    private String content;
}
