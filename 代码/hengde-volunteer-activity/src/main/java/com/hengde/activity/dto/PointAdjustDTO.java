package com.hengde.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员手工调整积分入参。
 *
 * <p>调整可正可负，但**必须填原因**——手工改积分绕过了活动发放的既定公式，
 * 不留原因日后无法追溯，故 {@code reason} 强制非空并落入流水 {@code remark} 供志愿者可见。</p>
 *
 * <p><b>{@code requestId} 也必填</b>：手工调整没有天然单据，流水的 {@code source_id} 只能为 null，
 * 而 MySQL 唯一索引视多个 NULL 互不相同——{@code uk_source} 对它形同虚设，双击、超时重试、网关重放
 * 都会实打实地重复加减分。故约定由前端<b>每次打开调整弹窗生成一个 UUID</b> 随请求带上：
 * 同一次调整重放多少次都只入账一次，而管理员连着做两次不同的调整是两个不同的 UUID，互不影响。</p>
 *
 * @author hengde
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PointAdjustDTO {

    /** 目标志愿者 id */
    @NotNull(message = "志愿者不能为空")
    private Long volunteerId;

    /** 变动值：正=加分，负=扣分；不可为 0 */
    @NotNull(message = "调整分值不能为空")
    private Integer changeAmount;

    /** 调整原因，志愿者在明细里可见 */
    @NotBlank(message = "请填写调整原因")
    @Size(max = 200, message = "调整原因过长")
    private String reason;

    /**
     * 幂等键：前端每次打开调整弹窗生成一个 UUID，重放同一次调整只入账一次。
     *
     * <p><b>限 ASCII 安全字符集</b>（与 {@code PointService.REQUEST_ID_PATTERN} 同一条规则）：
     * {@code uk_request_id} 的「相等」由排序规则说了算，而 Java 的 {@code equals} 由码点说了算，
     * 两者不重合的地方（重音折叠、全角、尾空格）就是系统保留前缀守卫的漏洞。
     * 限死字符集后这类等价不再存在；V33 另把该列改成了 {@code utf8mb4_0900_bin}。</p>
     */
    @NotBlank(message = "缺少幂等键 requestId")
    @Size(max = 64, message = "requestId 过长")
    @Pattern(regexp = "[A-Za-z0-9:._-]+", message = "requestId 只能由字母、数字与 : . _ - 组成")
    private String requestId;
}
