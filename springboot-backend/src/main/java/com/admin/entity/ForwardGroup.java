package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 转发自定义分组实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("forward_group")
public class ForwardGroup extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String groupName;
}
