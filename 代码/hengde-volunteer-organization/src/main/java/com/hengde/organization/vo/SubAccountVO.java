package com.hengde.organization.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 子账号出参。{@code permissionCodes} 仅在详情接口填充。
 *
 * @author hengde
 */
@Data
public class SubAccountVO {

    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String department;

    /** 状态 0启用/1禁用 */
    private Integer status;

    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;

    /** 已分配权限点编码（详情接口才有） */
    private List<String> permissionCodes;
}
