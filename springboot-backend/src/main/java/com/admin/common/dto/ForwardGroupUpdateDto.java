package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class ForwardGroupUpdateDto {

    @NotNull(message = "转发ID不能为空")
    private Long id;

    @Size(max = 100, message = "分组名称长度不能超过100")
    private String groupName;
}
