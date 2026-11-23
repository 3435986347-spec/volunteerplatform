package com.hengde.common.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充处理器。
 *
 * <p>配合 {@link com.hengde.common.entity.BaseEntity} 上的 {@code @TableField(fill=...)}：
 * 当执行 insert / update 时，MyBatis-Plus 会回调这里，自动给 createTime、updateTime、
 * isDeleted 赋值，业务代码就不用每次手写「new 一个时间塞进去」了。</p>
 *
 * @author hengde
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 创建时间字段名（与 BaseEntity 的属性名一致） */
    private static final String CREATE_TIME = "createTime";

    /** 更新时间字段名 */
    private static final String UPDATE_TIME = "updateTime";

    /** 逻辑删除字段名 */
    private static final String IS_DELETED = "isDeleted";

    /**
     * 插入时填充：创建时间、更新时间都设为当前时间，逻辑删除标记设为 0（未删除）。
     *
     * @param metaObject 当前被插入的实体的元对象（MyBatis-Plus 包装后传入）
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // strictInsertFill：只有当字段标了 FieldFill.INSERT 且当前为 null 时才填，避免覆盖已有值
        this.strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, IS_DELETED, Integer.class, 0);
    }

    /**
     * 更新时填充：只刷新更新时间为当前时间。
     *
     * @param metaObject 当前被更新的实体的元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
    }
}
