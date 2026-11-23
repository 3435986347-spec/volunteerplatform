package com.hengde.activity.dto;

import lombok.Data;

/**
 * 积分发放入参。{@code pointsFactor} 用于违规时调整：0正常/1减半/2不发（默认 0）。
 *
 * @author hengde
 */
@Data
public class GrantPointsDTO {

    /** 积分调整 0正常/1减半/2不发 */
    private Integer pointsFactor;
}
