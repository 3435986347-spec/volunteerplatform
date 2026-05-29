package com.hengde.common.constant;

/**
 * 用户状态常量。
 *
 * <p>对应用户表里的「状态」字段取值。用接口集中定义常量，业务里写
 * {@code UserStatus.NORMAL} 比直接写魔法数字 1 更易读、不易写错。</p>
 *
 * @author hengde
 */
public interface UserStatus {

    /** 正常：可正常登录和使用。取 0，与数据库 int 列默认值对齐，避免漏赋值时新用户被误判为禁用 */
    Integer NORMAL = 0;

    /** 禁用：被管理员封禁，无法登录 */
    Integer BANNED = 1;

    /**
     * 注销：用户主动注销账号。
     *
     * <p>注意：这是「业务状态」，表示账号被用户注销；它与 {@code BaseEntity.isDeleted}
     * （行级逻辑删除，{@code @TableLogic} 会让查询自动过滤掉该行）是两个不同概念，不要混用——
     * 一个是「账号还在、状态为已注销」，一个是「这行数据在查询里被隐藏」。</p>
     */
    Integer DELETED = 2;
}
