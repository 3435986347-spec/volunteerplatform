package com.hengde.honor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 删除某人证书时的留痕信息（Row 36 F 列第 ② 项「删除指定某人证书」）。
 *
 * @author hengde
 */
@Data
public class CertificateDeleteDTO {

    /**
     * 删除原因，<b>必填</b>。
     *
     * <p>删证书是运营纠错动作、且证书可被恢复，事后必须能查清「当时为什么删」。
     * 允许空原因等于让这条留痕形同虚设。</p>
     */
    @NotBlank(message = "删除原因不能为空")
    @Size(max = 512, message = "删除原因不超过 512 字")
    private String reason;
}
