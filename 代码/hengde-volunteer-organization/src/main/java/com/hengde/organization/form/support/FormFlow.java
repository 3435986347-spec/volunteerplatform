package com.hengde.organization.form.support;

import java.util.Set;

/**
 * 问卷的场景与状态码（V4 问卷引擎批）。
 *
 * @author hengde
 */
public final class FormFlow {

    /** 通用问卷：可同时收集多份，志愿者端在问卷列表里看到。 */
    public static final int SCENE_GENERAL = 1;
    /** 报名管理团队（Row 46）：答卷随申请一起提交，不能单独填。 */
    public static final int SCENE_MANAGER_APPLICATION = 2;
    /** 评优评先（Row 47「需预留」）：本批只做收集，评选流程另议。 */
    public static final int SCENE_EXCELLENCE = 3;
    /** 意见反馈（Row 48，个人中心补全批接）。 */
    public static final int SCENE_FEEDBACK = 4;
    /** 投诉建议（Row 43，投诉建议批接）。 */
    public static final int SCENE_COMPLAINT = 5;

    /**
     * 志愿者可以<b>直接</b>填写的场景。报名管理团队 / 投诉建议的答卷只能随各自的单据提交（否则会留下孤儿答卷）；
     * 意见反馈（Row 48「类似于问卷收集 / 后台设置」）自个人中心补全批起放开——它没有处理流程，收上来就是看。
     */
    public static final Set<Integer> DIRECT_SUBMIT_SCENES = Set.of(SCENE_GENERAL, SCENE_EXCELLENCE, SCENE_FEEDBACK);

    /**
     * 出现在志愿者「问卷列表」里的场景。意见反馈能直接填但不进列表——它的入口在安全中心
     * （{@code GET /v/organization/forms/scenes/4/current}），混进问卷列表会让人以为协会在做调查。
     */
    public static final Set<Integer> LISTED_SCENES = Set.of(SCENE_GENERAL, SCENE_EXCELLENCE);

    public static final int DRAFT = 0;
    public static final int COLLECTING = 1;
    public static final int CLOSED = 2;

    private FormFlow() {
    }

    public static boolean isValidScene(Integer s) {
        return s != null && s >= SCENE_GENERAL && s <= SCENE_COMPLAINT;
    }

    public static String sceneLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case SCENE_GENERAL -> "通用问卷";
            case SCENE_MANAGER_APPLICATION -> "报名管理团队";
            case SCENE_EXCELLENCE -> "评优评先";
            case SCENE_FEEDBACK -> "意见反馈";
            case SCENE_COMPLAINT -> "投诉建议";
            default -> "未知";
        };
    }

    public static String statusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case DRAFT -> "草稿";
            case COLLECTING -> "收集中";
            case CLOSED -> "已停止";
            default -> "未知";
        };
    }
}
