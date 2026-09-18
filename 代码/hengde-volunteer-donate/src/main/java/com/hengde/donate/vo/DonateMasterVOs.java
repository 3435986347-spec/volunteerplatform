package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 捐书批的两类主数据出参：受赠单位、商品条码库。
 *
 * @author hengde
 */
public final class DonateMasterVOs {

    private DonateMasterVOs() {
    }

    @Data
    @Schema(description = "受赠单位")
    public static class RecipientOrg {
        private Long id;
        private String name;
        @Schema(description = "1学校/2乡镇/3其他")
        private Integer orgType;
        private String address;
        private String contactName;
        private String contactPhone;
        @Schema(description = "1启用/0停用")
        private Integer status;
        private Integer sort;
        private LocalDateTime createTime;
    }

    @Data
    @Schema(description = "商品条码库条目")
    public static class Catalog {
        private Long id;
        private String barcode;
        private String name;
        private Integer itemType;
        private String itemTypeLabel;
        private String spec;
        private String remark;
    }
}
