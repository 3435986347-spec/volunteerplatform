package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 修改商品条码库条目（Row 17 D）。
 *
 * @author hengde
 */
@Data
@Schema(description = "商品条码库条目")
@JsonIgnoreProperties(ignoreUnknown = true)
public class BarcodeCatalogSaveDTO {

    @Schema(description = "商品条码（ISBN / EAN）")
    @NotBlank(message = "请填写条码")
    @Size(max = 64, message = "条码过长")
    private String barcode;

    @Schema(description = "商品名（书名）")
    @NotBlank(message = "请填写商品名")
    @Size(max = 255, message = "商品名过长")
    private String name;

    @Schema(description = "1课外书籍/2学习用品/3运动器材/9其他，不填=课外书籍")
    private Integer itemType;

    @Schema(description = "规格 / 作者 / 出版社")
    @Size(max = 255, message = "规格过长")
    private String spec;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注过长")
    private String remark;
}
