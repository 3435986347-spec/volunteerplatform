package com.hengde.publicity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BannerDTO {
    @NotBlank
    private String title;
    @NotBlank
    private String imageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer sort;
    private Integer status;
}
