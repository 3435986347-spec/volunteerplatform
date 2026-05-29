package com.hengde.publicity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SortDTO {
    @NotNull
    private Integer sort;
}
