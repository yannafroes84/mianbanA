package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class ForwardGroupsResponseDto {

    private List<ForwardGroupViewDto> customGroups;

    private List<ForwardGroupViewDto> usedGroups;

    private List<String> groups;
}
