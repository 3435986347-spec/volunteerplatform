package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 核销员（管理端）。
 *
 * @author hengde
 */
@Data
@Schema(description = "核销员")
public class MallVerifierVO {

    @Schema(description = "记录 id（撤销时用）")
    private Long id;

    @Schema(description = "志愿者 id")
    private Long volunteerId;

    @Schema(description = "志愿者姓名")
    private String volunteerName;

    @Schema(description = "作用域企业 id；为空=可核销全部商品（V3 恒为空）")
    private Long enterpriseId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "指派人 admin_user.id")
    private Long createBy;

    @Schema(description = "指派时间")
    private LocalDateTime createTime;
}
