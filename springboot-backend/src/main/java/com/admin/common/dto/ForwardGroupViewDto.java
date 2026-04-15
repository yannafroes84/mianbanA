package com.admin.common.dto;

import lombok.Data;

@Data
public class ForwardGroupViewDto {

    private Long id;

    private Integer userId;

    private String userName;

    private String groupName;

    private Long forwardCount;

    private Long createdTime;

    private Long updatedTime;

    private Boolean custom;
}
