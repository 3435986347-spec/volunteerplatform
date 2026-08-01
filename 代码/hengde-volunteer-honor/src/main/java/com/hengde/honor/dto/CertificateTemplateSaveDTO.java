package com.hengde.honor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/修改证书电子样本。
 *
 * @author hengde
 */
@Data
public class CertificateTemplateSaveDTO {

    /**
     * 绑定的活动 id；<b>不传 = 全局默认样本</b>。
     *
     * <p>不收 {@code scopeKey} 而收 {@code activityId}：作用域键由服务端组装，
     * 前端传不出「activity:abc」这类永远匹配不上的字符串。</p>
     */
    private Long activityId;

    @NotBlank(message = "样本名称不能为空")
    @Size(max = 128, message = "样本名称不超过 128 字")
    private String name;

    @NotBlank(message = "样本文件不能为空")
    @Size(max = 512, message = "文件 key 过长")
    private String fileKey;

    /** 字段与盖章坐标配置（JSON 文本） */
    private String layout;

    /** 启用 0否/1是，缺省 1 */
    private Integer enabled;
}
