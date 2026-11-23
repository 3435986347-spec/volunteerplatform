package com.hengde.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 志愿者证（V4 志愿者证批，Row 26）。
 *
 * @author hengde
 */
public final class VolunteerCardVO {

    private VolunteerCardVO() {
    }

    @Data
    @Schema(description = "我的志愿者证（本人看，信息完整）")
    public static class Mine {
        @Schema(description = "志愿者编号（volunteer.id）")
        private String no;
        private String realName;
        private String avatarUrl;
        private String genderName;
        @Schema(description = "注册（实名）时间")
        private LocalDateTime registerTime;
        @Schema(description = "所属职务：活动临时负责人 / 志愿者")
        private String duty;
        @Schema(description = "归属分队名称")
        private String squadName;
        @Schema(description = "累计服务时长（小时，一位小数；秘书部已确认的）")
        private BigDecimal serviceHours;
        @Schema(description = "参加活动次数（有签到的）")
        private Long activityCount;
        @Schema(description = "当前令牌签发时间（重置后变）")
        private LocalDateTime issueTime;
        @Schema(description = "MINIAPP_CODE 小程序码 / QR_CODE 普通二维码（小程序码生成不了时的回退）")
        private String qrType;
        @Schema(description = "码图 data:image/png;base64,…")
        private String qrImage;
        @Schema(description = "码里的内容：小程序码为 scene（令牌），普通二维码为核验地址或 hengde-volunteer-card:令牌")
        private String qrContent;
    }

    @Data
    @Schema(description = "扫码核验看到的志愿者信息（公开，Q5：不含手机号与学校）")
    public static class Public {
        private String no;
        @Schema(description = "姓名（只保留姓）")
        private String maskedName;
        private String avatarUrl;
        private LocalDateTime registerTime;
        private BigDecimal serviceHours;
        private Long activityCount;
        @Schema(description = "核验时间（服务器现在）")
        private LocalDateTime verifyTime;
    }
}
