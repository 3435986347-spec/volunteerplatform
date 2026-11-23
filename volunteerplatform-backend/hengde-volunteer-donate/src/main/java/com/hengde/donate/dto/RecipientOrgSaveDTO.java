package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 修改受赠单位（清单 ⑤ 默认的主数据表）。
 *
 * @author hengde
 */
@Data
@Schema(description = "受赠单位")
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecipientOrgSaveDTO {

    @Schema(description = "单位名称（未删行中唯一）")
    @NotBlank(message = "请填写单位名称")
    @Size(max = 128, message = "单位名称不超过 128 字")
    private String name;

    @Schema(description = "1学校/2乡镇/3其他，不填=学校")
    private Integer orgType;

    @Schema(description = "地址")
    @Size(max = 255, message = "地址过长")
    private String address;

    @Schema(description = "联系人")
    @Size(max = 64, message = "联系人过长")
    private String contactName;

    @Schema(description = "联系电话")
    @Size(max = 32, message = "联系电话过长")
    private String contactPhone;

    @Schema(description = "1启用/0停用，不填=启用")
    private Integer status;

    @Schema(description = "排序")
    private Integer sort;
}
