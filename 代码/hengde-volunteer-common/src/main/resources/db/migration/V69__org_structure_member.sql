-- 组织架构维护（V4 组织架构维护批）：架构节点里放人。
--
-- 【需求出处】xlsx Row 6「组织架构 / 展示名字、部门、职位、电话 / 就跟架构一样，由不同的小方框组成，然后我们插入那个志愿者之后
--   那个志愿者的信息就会显示在那个架构里面显示他是哪个部门的什么职位，框架新增、减少，均有最高权限操作」；
--   Row 5 F「需要一个修改部门功能，在这里输入他的职位信息，他在前端就可以直接在名字下面展示」。
--
-- 【一个人在架构里至多一个位置】「显示他是哪个部门的什么职位」是单数；名字下面也只放得下一行。生成列唯一键兜底。
--   V1 在 volunteer 上留过一列 position（「后台设置，前端名字下展示」），一直没有写入口；自本批起名字下面那一行
--   由架构位置现算（部门 · 职位），那一列不再写——两处都能改「职位」，迟早各说各的。
--
-- 【节点】沿用 V5 的 organization_structure_node（parent_id / name / title / sort）。V5 种子里「秘书部」「宣传部」等
--   节点名就是部门名。节点的增删改、放人、挪人都要 org:structure（Row 6「均有最高权限操作」，超管 * 通配）。
--
-- 【本文件形态】一条 CREATE TABLE（新表）+ 一个权限点。

CREATE TABLE organization_structure_member (
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    node_id              BIGINT      NOT NULL COMMENT '所在架构节点（部门）',
    volunteer_id         BIGINT      NOT NULL COMMENT '志愿者',
    position             VARCHAR(64) NOT NULL COMMENT '职位（名字下面展示）',
    sort                 INT         NOT NULL DEFAULT 0 COMMENT '节点内排序',
    create_time          DATETIME    NOT NULL COMMENT '创建时间',
    update_time          DATETIME             DEFAULT NULL COMMENT '更新时间',
    is_deleted           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_volunteer_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN volunteer_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_volunteer (active_volunteer_key),
    KEY idx_node_sort (node_id, sort, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '组织架构里的人（Row 6）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('org:structure', '组织架构维护（节点增删改、放人挪人）', 'org', 2, 78, NOW(), 0);
