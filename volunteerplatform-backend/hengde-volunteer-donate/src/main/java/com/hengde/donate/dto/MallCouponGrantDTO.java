package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 发卷 / 批量发卷（Row 8 F「发放卷功能、批量发卷功能」）。
 *
 * <p>对象可以按志愿者 id 给，也可以按手机号给（后台常见的是拿一张名单表来发），两者可同时给、合并去重。</p>
 *
 * <p><b>{@code requestId} 必填</b>：前端每次打开发卷弹窗生成一个（如 UUID），同一次发放的重放
 * （双击、弱网重试）只会发一次。字符集限死在 ASCII 安全集——与积分调整
 * {@code PointAdjustDTO.requestId} 同一条（V34 那一课：库里的「相等」由排序规则决定，
 * 不在 Java 里追着枚举等价类，而是只收不会有歧义的字符）。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "发卷")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallCouponGrantDTO {

    @Schema(description = "本次发放的幂等键（前端每次打开发卷弹窗生成）")
    @NotBlank(message = "缺少幂等键 requestId")
    @Pattern(regexp = "[A-Za-z0-9:._-]{1,64}", message = "幂等键只能由字母、数字与 : . _ - 组成，且不超过 64 位")
    private String requestId;

    @Schema(description = "按志愿者 id 发放")
    private List<Long> volunteerIds;

    @Schema(description = "按手机号发放（查不到或一号多账号的会逐条列出，不会随便挑一个）")
    private List<String> phones;
}
