package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.organization.biz.dao.OrganizationStructureNodeMapper;
import com.hengde.organization.biz.entity.OrganizationStructureNode;
import com.hengde.organization.biz.vo.StructureNodeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StructureService {

    private OrganizationStructureNodeMapper nodeMapper;

    @Autowired
    public void setNodeMapper(OrganizationStructureNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    public List<StructureNodeVO> tree() {
        List<StructureNodeVO> nodes = nodeMapper.selectList(Wrappers.<OrganizationStructureNode>lambdaQuery()
                        .orderByAsc(OrganizationStructureNode::getSort)
                        .orderByAsc(OrganizationStructureNode::getId))
                .stream()
                .map(this::toVO)
                .toList();
        Map<Long, List<StructureNodeVO>> byParent = nodes.stream()
                .filter(node -> node.getParentId() != null)
                .collect(Collectors.groupingBy(StructureNodeVO::getParentId));
        for (StructureNodeVO node : nodes) {
            List<StructureNodeVO> children = new ArrayList<>(byParent.getOrDefault(node.getId(), List.of()));
            children.sort(Comparator.comparing(StructureNodeVO::getSort).thenComparing(StructureNodeVO::getId));
            node.setChildren(children);
        }
        return nodes.stream().filter(node -> node.getParentId() == null).toList();
    }

    private StructureNodeVO toVO(OrganizationStructureNode node) {
        StructureNodeVO vo = new StructureNodeVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setName(node.getName());
        vo.setTitle(node.getTitle());
        vo.setSort(node.getSort());
        return vo;
    }
}
