package com.hengde.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 负责人记录违规入参。
 *
 * @author hengde
 */
@Data
public class ViolationDTO {

    /** 类型 1玩手机/2服装不合格/3早退/4长时间交头接耳/0其他 */
    @NotNull(message = "违规类型不能为空")
    private Integer violationType;

    /** 违规说明 */
    private String description;
}
