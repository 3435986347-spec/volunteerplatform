package com.hengde.enterprise.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 爱心企业出参（V4 爱心企业批）。
 *
 * @author hengde
 */
public final class EnterpriseVOs {

    private EnterpriseVOs() {
    }

    @Data
    @Schema(description = "登录结果")
    public static class LoginResult {
        private String token;
        private Long enterpriseId;
        @Schema(description = "0待审核/1正常/2已驳回（待审核与驳回只能看改自己的资料）")
        private Integer status;
        private String statusLabel;
    }

    @Data
    @Schema(description = "企业资料（企业本人与后台看）")
    public static class Account {
        @Schema(description = "企业编号")
        private Long id;
        private String name;
        private String creditCode;
        private String logoUrl;
        private String intro;
        private String address;
        private String contactPhone;
        private String leaderName;
        @Schema(description = "负责人手机号（明文）")
        private String leaderPhone;
        private String username;
        private Integer status;
        private String statusLabel;
        @Schema(description = "1自助注册/2后台代建")
        private Integer source;
        private LocalDateTime submitTime;
        private String rejectReason;
        private String auditByName;
        private LocalDateTime auditTime;
        private String pauseReason;
        private LocalDateTime pausedTime;
        private LocalDateTime lastLoginTime;
        private LocalDateTime createTime;
    }

    @Data
    @Schema(description = "爱心企业（志愿者端公开展示：Row 15「编号、照片、店名、地址、电话、详情」）")
    public static class Card {
        @Schema(description = "企业编号")
        private Long id;
        private String name;
        private String logoUrl;
        private String address;
        private String contactPhone;
        @Schema(description = "介绍（列表截前 60 字，详情为全文）")
        private String intro;
    }
}
