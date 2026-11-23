package com.hengde.social.constant;

import java.util.Set;

/**
 * 社区的状态码与取值（单一来源）。
 *
 * @author hengde
 */
public final class SocialCodes {

    private SocialCodes() {
    }

    /** 作者：志愿者 */
    public static final int AUTHOR_VOLUNTEER = 1;
    /** 作者：官方（后台账号） */
    public static final int AUTHOR_OFFICIAL = 2;

    /** 作者类型：爱心企业（V4 爱心企业批·社区段，Row 15 F「登录企业账号之后也可以进行发帖」） */
    public static final int AUTHOR_ENTERPRISE = 3;

    public static final int MEDIA_NONE = 0;
    public static final int MEDIA_IMAGE = 1;
    public static final int MEDIA_VIDEO = 2;

    /** 不限制（默认） */
    public static final int VISIBLE_ALL = 0;
    /** 隐藏：只有自己看得到 */
    public static final int VISIBLE_SELF = 1;
    /** 我关注的人可看 */
    public static final int VISIBLE_MY_FOLLOWING = 2;
    /** 关注我的人可看 */
    public static final int VISIBLE_MY_FOLLOWERS = 3;

    public static final Set<Integer> VISIBILITIES = Set.of(VISIBLE_ALL, VISIBLE_SELF, VISIBLE_MY_FOLLOWING, VISIBLE_MY_FOLLOWERS);

    public static final int REVIEW_PENDING = 0;

    /** 上传目录：帖子图片 / 帖子视频（只收本系统传到这两个目录下的） */
    public static final String DIR_IMAGE = "social";
    public static final String DIR_VIDEO = "social-video";

    public static final int MAX_IMAGES = 9;
    public static final int MAX_CONTENT = 2000;
    public static final int MAX_COMMENT = 500;
    public static final int MAX_BIO = 200;
    public static final int MAX_LABEL = 128;

    /** 帖子流页签 */
    public static final String TAB_LATEST = "latest";
    public static final String TAB_HOT = "hot";
    public static final String TAB_FOLLOWING = "following";
    public static final String TAB_OFFICIAL = "official";

    public static String visibilityLabel(Integer v) {
        if (v == null) {
            return "不限制";
        }
        return switch (v) {
            case VISIBLE_SELF -> "隐藏";
            case VISIBLE_MY_FOLLOWING -> "我的关注可看";
            case VISIBLE_MY_FOLLOWERS -> "关注我的可看";
            default -> "不限制";
        };
    }
}
