package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

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

    /** 类型 1个人/2团队，见 {@link com.hengde.honor.constant.RoleModelType}（V43） */
    private Integer modelType;

    /** 副标题 */
    private String subtitle;

    /** 简介（小程序列表卡片正文，V43） */
    private String summary;

    /** 图片 URL */
    private String imageUrl;

    /** 跳转类型，见 {@link com.hengde.honor.constant.RoleModelLinkType}（V43） */
    private Integer linkType;

    /** 跳转链接（推文等） */
    private String linkUrl;

    /**
     * 首次上架时间（V43）。
     *
     * <p>不等于 {@code createTime}：运营常先建好一批草稿再统一上架，
     * 按创建时间排会得到录入顺序而不是发布顺序。下架不清空、再次上架不覆盖——
     * 「发布时间」说的是这条内容第一次与志愿者见面的时刻，临时下架再上架
     * 不该让它跳到列表最前面。</p>
     */
    private LocalDateTime publishTime;

    /** 展示排序，小的在前 */
    private Integer sort;

    /** 状态 0下架/1上架 */
    private Integer status;
}
