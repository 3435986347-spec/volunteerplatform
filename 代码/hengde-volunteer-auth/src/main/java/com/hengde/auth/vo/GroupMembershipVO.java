package com.hengde.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 企业微信群成员前置校验返回。
 *
 * @author hengde
 */
@Data
@AllArgsConstructor
public class GroupMembershipVO {

    /** 是否已入群 */
    private boolean member;

    /** 未入群时引导加入的群二维码地址（已入群为 null） */
    private String qrUrl;
}
