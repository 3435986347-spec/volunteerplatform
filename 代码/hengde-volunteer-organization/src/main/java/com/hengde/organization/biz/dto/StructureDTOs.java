package com.hengde.organization.biz.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 组织架构维护入参（V4 组织架构维护批）。
 *
 * @author hengde
 */
public final class StructureDTOs {

    private StructureDTOs() {
    }

    @Data
    @Schema(description = "新增 / 修改架构节点")
    public static class NodeSave {

        @Schema(description = "上级节点（新增必填；根节点修改时不传）")
        private Long parentId;

        @NotBlank(message = "请填写名称")
        @Size(max = 64, message = "名称不超过 64 字")
        private String name;

        @Schema(description = "说明")
        @Size(max = 64, message = "说明不超过 64 字")
        private String title;

        private Integer sort;
    }

    @Data
    @Schema(description = "把志愿者放进节点")
    public static class MemberSave {

        @NotNull(message = "请选择志愿者")
        private Long volunteerId;

        @NotBlank(message = "请填写职位")
        @Size(max = 64, message = "职位不超过 64 字")
        private String position;

        private Integer sort;
    }

    @Data
    @Schema(description = "修改职位 / 排序 / 挪到另一个节点")
    public static class MemberUpdate {

        @Schema(description = "挪到哪个节点（不传＝不挪）")
        private Long nodeId;

        @NotBlank(message = "请填写职位")
        @Size(max = 64, message = "职位不超过 64 字")
        private String position;

        private Integer sort;
    }
}
