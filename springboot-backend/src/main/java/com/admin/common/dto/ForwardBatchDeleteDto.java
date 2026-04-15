package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class ForwardBatchDeleteDto {

    @NotEmpty(message = "转发ID列表不能为空")
    private List<Long> ids;

    private Boolean forceDelete = Boolean.FALSE;
}
