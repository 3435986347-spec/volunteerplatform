package com.hengde.publicity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PublicityFileDTO {
    @NotBlank
    private String fileName;
    @NotBlank
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer downloadable;
    private Integer sort;
}
