package com.hengde.organization.biz.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GroupCreateDTO {
    @NotBlank
    private String name;
    private String description;
}
