package com.hengde.social.constant;

/**
 * 社区权限点（V71）。
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /** 官方帖：本部门发布、删除，以官方身份回复评论、删除本部门官方帖下的评论（Row 23「由各部分负责人在后台发布……可以自由删除、回复、评论」） */
    public static final String SOCIAL_OFFICIAL = "social:official";

    /** 官方帖全部门：删除其他部门发的官方帖与评论（Row 23「宣传部可以删除其他部门发的帖子」） */
    public static final String SOCIAL_OFFICIAL_ALL = "social:official-all";

    /** 帖子与评论管理：两个按时间排序的列表、隐藏、删除、置顶（Row 23 F，V72） */
    public static final String SOCIAL_POST_MANAGE = "social:post-manage";

    /** 帖子审核（还须被设为某一级审核员；超管不必） */
    public static final String SOCIAL_REVIEW = "social:review";

    /** 审核设置：审核级数、审核员、风控关键词 */
    public static final String SOCIAL_REVIEW_SETTING = "social:review-setting";

    /** 举报处理 */
    public static final String SOCIAL_REPORT = "social:report";

    /** 社区禁言（禁止发帖 / 评论 / 点赞几天，即时生效，Q2） */
    public static final String SOCIAL_BAN = "social:ban";

    /** 看社区发布人的真实姓名与学校（Row 23 F「最高权限管理员才可以看到」，默认不授任何人，超管通配） */
    public static final String SOCIAL_REAL_NAME = "social:real-name";

    /**
     * 聊天记录查看与私聊投诉处理（Row 23 F「所有聊天记录均保存在系统后台、查看权限仅为最高管理员，
     * 亦可由管理员下放该功能至某个账号使用」，V82）。
     *
     * <p><b>默认不授任何人</b>（超管通配），与 {@link #SOCIAL_REAL_NAME} 同一形状：私聊内容比帖子敏感得多，
     * 「下放」必须是一次显式动作。处理私聊投诉也挂这个点——不看内容就判不了，拆成两个点等于让人盲审。</p>
     */
    public static final String SOCIAL_CHAT_VIEW = "social:chat-view";
}
