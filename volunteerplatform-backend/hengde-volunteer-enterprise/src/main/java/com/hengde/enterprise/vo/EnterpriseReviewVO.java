package com.hengde.enterprise.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 赞助商评价出参。<b>对外只给「谁（只留姓）· 打了几分 · 说了什么 · 什么时候」</b>；兑换单 id 与评价人 id 只在后台与本人那一侧有意义，
 * 公开列表里不带（同商品评价的口径）。
 *
 * @author hengde
 */
@Data
@Schema(description = "赞助商评价")
public class EnterpriseReviewVO {
    private Long id;
    private Long enterpriseId;
    private String enterpriseName;
    @Schema(description = "评价人（只留姓）")
    private String volunteerMaskedName;
    private Integer rating;
    private String content;
    @Schema(description = "0正常/1已屏蔽（仅后台与企业自己看得到这一位）")
    private Integer status;
    private String hiddenReason;
    private LocalDateTime createTime;
}
