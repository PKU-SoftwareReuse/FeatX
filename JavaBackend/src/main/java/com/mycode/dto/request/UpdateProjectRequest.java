package com.mycode.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProjectRequest {
    private String projectName;

    private String description;

    private String descriptionCn;

    private String gitLink;
}
