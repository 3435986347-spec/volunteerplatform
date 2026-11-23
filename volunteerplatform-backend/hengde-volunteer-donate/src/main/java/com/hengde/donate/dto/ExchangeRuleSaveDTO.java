package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 兑换规则的保存入参。
 *
 * <p><b>没有状态位、没有生效时间</b>——Row 8 只要「一份当前规则」，
 * 加上下架或多版本是替协会做决定。真要历史版本，那时另加一张表，本 DTO 不用改。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "积分兑换规则")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeRuleSaveDTO {

    @Schema(description = "规则正文（富文本）")
    @Size(max = 20000, message = "规则正文过长")
    private String content;

    /** 图片走 {@code POST /a/files/upload?dir=exchange-rule}（权限 donate:goods，仅图片）。 */
    @Schema(description = "配图 URL 列表")
    private List<@Size(max = 512, message = "图片 URL 过长") String> images;
}
