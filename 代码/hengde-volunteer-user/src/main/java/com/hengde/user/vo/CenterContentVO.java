package com.hengde.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人中心内容。
 *
 * @author hengde
 */
@Data
@Schema(description = "个人中心内容（我的保险 / 联系客服）")
public class CenterContentVO {
    @Schema(description = "insurance 我的保险 / customer-service 联系客服")
    private String key;
    private String title;
    private String body;
    private List<String> images = new ArrayList<>();
    private LocalDateTime updateTime;
}
