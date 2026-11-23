package com.hengde.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库实体基类。
 *
 * <p>把“每张表都有”的公共字段抽到这里，业务实体只需 {@code extends BaseEntity}，
 * 不必重复写主键和时间戳。</p>
 *
 * <p>其中 createTime / updateTime 不需要业务代码手动赋值，
 * 由 {@link com.hengde.common.handler.MyMetaObjectHandler} 在插入/更新时自动填充。</p>
 *
 * @author hengde
 */
@Data
public class BaseEntity {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建时间：仅在 INSERT 时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间：INSERT 和 UPDATE 时都自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记，0=未删除，1=已删除。
     *
     * <p>{@code @TableLogic} 让 MyBatis-Plus 的删除变成“改这个字段”而非真正 DELETE，
     * 查询时也会自动带上“未删除”条件，业务代码无感知。</p>
     */
    @TableField(fill = FieldFill.INSERT)
    @TableLogic
    private Integer isDeleted;
}
