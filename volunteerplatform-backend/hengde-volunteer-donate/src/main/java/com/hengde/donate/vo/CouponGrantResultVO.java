package com.hengde.donate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 发卷结果：逐类说清「发了几张、为什么有人没发到」。
 *
 * <p>批量操作只回一个「成功」会让漏掉的人永远没人知道——同证书批量上传「逐条回报成败」那条口径。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "发卷结果")
public class CouponGrantResultVO {

    @Schema(description = "本次去重后的目标人数")
    private int requested;

    @Schema(description = "本次新发出的张数")
    private int granted;

    @Schema(description = "此前同一 requestId 已发过、本次跳过的人数（重放）")
    private int alreadyGranted;

    @Schema(description = "查不到账号（或一号多账号）的手机号")
    private List<String> unmatchedPhones = new ArrayList<>();

    @Schema(description = "不可发放的志愿者 id（不存在 / 未实名 / 账号已停用或注销）")
    private List<Long> ineligibleVolunteerIds = new ArrayList<>();
}
