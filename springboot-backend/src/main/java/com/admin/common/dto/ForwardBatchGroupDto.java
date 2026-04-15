package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class ForwardBatchGroupDto {

    @NotEmpty(message = "转发ID列表不能为空")
    private List<Long> ids;

    @Size(max = 100, message = "分组名称长度不能超过100")
    private String groupName;
}
