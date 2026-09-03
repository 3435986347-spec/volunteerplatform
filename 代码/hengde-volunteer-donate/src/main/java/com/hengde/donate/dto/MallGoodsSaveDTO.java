package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 积分商品的新增 / 修改入参。
 *
 * <p><b>不含 sort / hidden</b>：那两个是纯展示字段，走 {@code PATCH .../display}——
 * 合到这里会让「临时隐藏一下」变成「重新走一遍审核」。</p>
 *
 * <p><b>也不含 status</b>：审核态只能由 submit / approve / reject 迁移，
 * 让入参能直接写 status 等于把审核闸门交给调用方。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "积分商品")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallGoodsSaveDTO {

    @Schema(description = "商品名称")
    @NotBlank(message = "请填写商品名称")
    @Size(max = 128, message = "商品名称不超过 128 字")
    private String name;

    @Schema(description = "商品图片 URL")
    @Size(max = 512, message = "图片 URL 过长")
    private String coverUrl;

    @Schema(description = "商品详情（图文）")
    private String detail;

    /**
     * 赞助方名称<b>快照</b>。
     *
     * <p>Row 8「商品赞助单位需要入驻爱心企业」，但 enterprise 模块不在 V3
     * ⇒ {@code sponsor_enterprise_id} 恒为 NULL，只留名称，由后台按
     * Row 15 F「后台可以以企业的名义代替企业发布积分商品」代发布。</p>
     */
    @Schema(description = "赞助方名称（V3 由后台代填，enterprise 模块未建）")
    @Size(max = 128, message = "赞助方名称不超过 128 字")
    private String sponsorName;

    /**
     * 规格与库存。<b>库存只存在规格上</b>，商品本身没有库存字段。
     *
     * <p>新增时至少一条；修改时传 null=不动规格，传列表=全量替换。</p>
     */
    @Schema(description = "规格列表（库存的唯一存放处）")
    @Valid
    private List<MallGoodsSpecDTO> specs;
}
