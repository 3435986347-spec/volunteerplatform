package com.hengde.organization.biz.controller;

import com.hengde.common.result.Result;
import com.hengde.organization.biz.service.StructureService;
import com.hengde.organization.biz.vo.StructureNodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "志愿者端-组织架构")
@RestController
@RequestMapping("/v/organization/structure")
public class StructureController {

    private StructureService structureService;

    @Autowired
    public void setStructureService(StructureService structureService) {
        this.structureService = structureService;
    }

    @Operation(summary = "组织架构树")
    @GetMapping
    public Result<List<StructureNodeVO>> tree() {
        return Result.ok(structureService.tree());
    }
}
