package com.hengde.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 负责人记录违规入参。
 *
 * <p>需求口径：违规以<b>自由文本</b>（{@link #description}=记录明细，<b>必填</b>）为主，类型可不填——
 * {@link #violationType} 可选，缺省由 service 记为 0（其他）。</p>
 *
 * @author hengde
 */
@Data
public class ViolationDTO {

    /** 类型 1玩手机/2服装不合格/3早退/4长时间交头接耳/0其他（可选，缺省 0） */
    private Integer violationType;

    /** 违规说明（记录明细，自由文本，必填，≤512 字与 DB activity_violation.description 对齐） */
    @NotBlank(message = "请填写违规说明")
    @Size(max = 512, message = "违规说明过长（不超过 512 字）")
    private String description;
}
