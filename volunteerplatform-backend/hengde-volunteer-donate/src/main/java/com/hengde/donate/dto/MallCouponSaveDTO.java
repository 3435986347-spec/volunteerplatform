package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建 / 修改卷定义。
 *
 * <p>字段间的约束（兑换卷必须指定商品、满减卷门槛不得低于抵扣、有效期先后）由
 * {@code MallCouponService} 统一校验——Bean Validation 表达不了字段之间的关系，
 * 写一半在注解、一半在服务层，下一个人只会看到其中一半。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "卷定义")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallCouponSaveDTO {

    @Schema(description = "卷名称")
    @NotBlank(message = "请填写卷名称")
    @Size(max = 64, message = "卷名称不超过 64 字")
    private String name;

    @Schema(description = "类型 1指定商品兑换卷（全额抵扣）/2积分满减卷")
    @NotNull(message = "请选择卷类型")
    private Integer type;

    @Schema(description = "适用商品 id：兑换卷必填；满减卷不填=全场通用")
    private Long goodsId;

    @Schema(description = "满减门槛（规格所需积分满多少可用），仅满减卷")
    private Integer thresholdPoints;

    @Schema(description = "满减抵扣积分，仅满减卷")
    private Integer discountPoints;

    @Schema(description = "有效期起（含）")
    @NotNull(message = "请填写有效期起")
    private LocalDateTime validStart;

    @Schema(description = "有效期止（不含）")
    @NotNull(message = "请填写有效期止")
    private LocalDateTime validEnd;

    @Schema(description = "使用说明")
    @Size(max = 512, message = "使用说明不超过 512 字")
    private String description;
}
