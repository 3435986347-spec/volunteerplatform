package com.hengde.activity.constant;

/**
 * 活动现场负责人来源类型码：1报名志愿者(volunteer.id) / 2管理团队(admin_user.id)。
 *
 * @author hengde
 */
public final class LeaderType {

    private LeaderType() {
    }

    /** 报名志愿者（refId=volunteer.id，须为本活动报名者） */
    public static final int VOLUNTEER = 1;
    /** 管理团队（refId=admin_user.id） */
    public static final int ADMIN = 2;
}
