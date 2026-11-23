package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建 / 修改捐书活动（Row 17）。
 *
 * @author hengde
 */
@Data
@Schema(description = "捐书活动")
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookCampaignSaveDTO {

    @Schema(description = "活动名称")
    @NotBlank(message = "请填写活动名称")
    @Size(max = 128, message = "活动名称不超过 128 字")
    private String title;

    @Schema(description = "封面 URL")
    @Size(max = 512, message = "封面 URL 过长")
    private String coverUrl;

    @Schema(description = "详情（文字 + 图片）")
    private String detail;

    @Schema(description = "开始时间")
    @NotNull(message = "请填写开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    @NotNull(message = "请填写结束时间")
    private LocalDateTime endTime;

    @Schema(description = "收件电话（Row 17「电话：后台预留」）")
    @Size(max = 32, message = "收件电话过长")
    private String recvPhone;

    @Schema(description = "收件地址前缀（完整地址 = 前缀 + 捐赠人姓名）")
    @Size(max = 255, message = "收件地址过长")
    private String recvAddress;

    @Schema(description = "本次活动数据的统计截止时间；不填=不截止")
    private LocalDateTime statsDeadline;
}
