package com.hengde.auth.integration;

/**
 * 企业微信群成员校验。注册前置：未入群则前端弹群二维码引导加入。
 *
 * <p>当前为 no-op 实现（{@link WeworkGroupServiceImpl}），真实接入企业微信后替换。</p>
 *
 * @author hengde
 */
public interface WeworkGroupService {

    /**
     * 校验该手机号对应的人是否已在企业微信群。
     *
     * @param phone 手机号（明文）
     * @return 已入群返回 true
     */
    boolean isGroupMember(String phone);

    /**
     * 未入群时引导用的群二维码地址（可能为 null）。
     */
    String getGroupQrUrl();
}
