package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 榜样，V28。
 *
 * <p>数据形态与 {@code publicity_banner} 同构（标题/图片/跳转/排序/上下架），
 * 刻意保持一致以便后台复用同一套交互。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_role_model")
public class HonorRoleModel extends BaseEntity {

    /** 标题 */
    private String title;

    /** 副标题 */
    private String subtitle;

    /** 图片 URL */
    private String imageUrl;

    /** 跳转链接（推文等） */
    private String linkUrl;

    /** 展示排序，小的在前 */
    private Integer sort;

    /** 状态 0下架/1上架 */
    private Integer status;
}
