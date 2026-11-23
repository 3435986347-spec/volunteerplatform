package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 核对捐赠单据（Row 17 第 6 步「机构扫码核对其捐赠物资是否符合他提供的捐赠单据」）。
 *
 * <p><b>核对是整个运单一次提交</b>——这是扫码流里唯一一个带列表的动作：扫的是一个包裹，
 * 判的是里面每一件。结果必须<b>覆盖该运单的全部待核对物资</b>，漏判一件就拒绝，
 * 否则会留下一件永远停在「已到货待核对」的东西。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "核对结果")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShipmentCheckDTO {

    @Schema(description = "逐件核对结果，须覆盖该运单全部待核对物资")
    @NotEmpty(message = "核对结果不能为空")
    @Valid
    private List<ItemResult> results;

    @Data
    @Schema(description = "单件核对结果")
    public static class ItemResult {

        @Schema(description = "物资 id")
        @NotNull(message = "物资 id 不能为空")
        private Long itemId;

        @Schema(description = "是否合格")
        @NotNull(message = "请判定是否合格")
        private Boolean qualified;

        @Schema(description = "说明（不合格时必填：为什么退回）")
        @Size(max = 255, message = "说明过长")
        private String remark;
    }
}
