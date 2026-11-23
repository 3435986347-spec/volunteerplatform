package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 报名并登记寄出（Row 17 第 1–4 步：报名、准备物品、录入物资、生成包裹输入快递单号与快递公司）。
 *
 * <p><b>一次提交</b>：四步在手机上本来就是同一张表单，拆成四个接口只会多出「报了名没寄」「寄了没录物资」
 * 这类半截状态。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "登记寄出")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShipmentRegisterDTO {

    @Schema(description = "快递公司编码（从 GET /v/donate/express-companies 里选）")
    @NotBlank(message = "请选择快递公司")
    private String expressCode;

    @Schema(description = "快递单号")
    @NotBlank(message = "请填写快递单号")
    @Size(max = 64, message = "快递单号过长")
    private String expressNo;

    @Schema(description = "捐赠人单位（Row 17 F 搜索与导出列），可空")
    @Size(max = 128, message = "单位名称过长")
    private String donorOrg;

    @Schema(description = "包裹里的物资")
    @NotEmpty(message = "请至少录入一件物资")
    @Size(max = 200, message = "一个包裹最多录入 200 行物资")
    @Valid
    private List<DonateItemInputDTO> items;
}
