package com.hengde.organization.biz.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SquadDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String type;
    private Long leaderId;
    private String leaderName;
    private String leaderPhone;
    private Integer memberLimit;
    private String visibleFields;
    private Integer status;
}
