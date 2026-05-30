package com.hengde.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开发登录入参（跳过微信，仅 {@code hengde.auth.dev-login-enabled=true} 时可用，禁用于生产）。
 *
 * @author hengde
 */
@Data
public class DevLoginDTO {

    /** 测试身份标识（不同 key 对应不同测试志愿者；留空默认 tester）。openid 落为 {@code dev:{key}} */
    @Size(max = 32, message = "key 不超过 32 字")
    private String key;

    /** 是否直接造成「已实名志愿者」（true 跳过注册流程，便于测试实名后功能；默认 false=游客态） */
    private Boolean registered;
}
