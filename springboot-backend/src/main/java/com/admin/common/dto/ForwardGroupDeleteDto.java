package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class ForwardGroupDeleteDto {

    @NotNull(message = "分组ID不能为空")
    private Long id;
}
