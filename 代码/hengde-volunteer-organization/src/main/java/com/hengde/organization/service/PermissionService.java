package com.hengde.organization.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.organization.dao.PermissionMapper;
import com.hengde.organization.entity.Permission;
import com.hengde.organization.vo.PermissionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限点目录查询（供超管勾选分配）。表里只有可分配的点，
 * 仅超管的 user:edit/org:perm-assign 不在其中，天然不会被分配。
 *
 * @author hengde
 */
@Service
public class PermissionService {

    private PermissionMapper permissionMapper;

    @Autowired
    public void setPermissionMapper(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    public List<PermissionVO> listAll() {
        return permissionMapper.selectList(
                        Wrappers.<Permission>lambdaQuery().orderByAsc(Permission::getSort))
                .stream().map(this::toVO).toList();
    }

    private PermissionVO toVO(Permission p) {
        PermissionVO vo = new PermissionVO();
        vo.setId(p.getId());
        vo.setCode(p.getCode());
        vo.setName(p.getName());
        vo.setModule(p.getModule());
        vo.setType(p.getType());
        vo.setSort(p.getSort());
        return vo;
    }
}
