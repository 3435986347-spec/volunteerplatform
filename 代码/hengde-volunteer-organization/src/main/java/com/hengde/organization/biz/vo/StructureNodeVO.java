package com.hengde.organization.biz.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织架构节点（含下级节点与节点里的人）。
 *
 * @author hengde
 */
@Getter
@Setter
public class StructureNodeVO {
    private Long id;
    private Long parentId;
    private String name;
    private String title;
    private Integer sort;
    private List<StructureNodeVO> children = new ArrayList<>();
    @Schema(description = "节点里的人（Row 6：名字、职位、电话；部门即节点名）")
    private List<Member> members = new ArrayList<>();

    /** 架构里的一个人。 */
    @Getter
    @Setter
    public static class Member {
        @Schema(description = "架构位置 id（修改 / 移除用）")
        private Long memberId;
        private Long volunteerId;
        private String name;
        private String position;
        @Schema(description = "电话（Row 6 原文要展示；只有进了架构的部门成员会下发）")
        private String phone;
        private Integer sort;
    }
}
