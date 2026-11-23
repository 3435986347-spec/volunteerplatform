package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兑换规则出参。
 *
 * <p>协会还没填写时返回<b>空内容而不是报错</b>：志愿者点开「兑换规则」看到一片空白是可接受的，
 * 看到一个错误提示则会以为程序坏了。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "积分兑换规则")
public class ExchangeRuleVO {

    @Schema(description = "规则正文（富文本）；未填写时为 null")
    private String content;

    @Schema(description = "配图 URL 列表；未填写时为空列表")
    private List<String> images;

    @Schema(description = "最后更新时间；未填写时为 null")
    private LocalDateTime updateTime;
}
