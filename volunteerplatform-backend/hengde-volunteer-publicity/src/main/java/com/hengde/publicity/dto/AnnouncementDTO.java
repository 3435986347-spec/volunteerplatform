package com.hengde.publicity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnnouncementDTO {
    @NotBlank
    private String title;
    private String summary;
    private String content;
    private String coverImageUrl;
    private Integer linkType;
    private String linkUrl;
    private Integer status;
}
