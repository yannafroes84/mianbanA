package com.admin.common.dto;

import lombok.Data;

/**
 * 转发列表查询条件
 */
@Data
public class ForwardQueryDto {

    private Integer inPort;

    private String groupName;
}
