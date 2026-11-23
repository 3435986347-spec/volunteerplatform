package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 现场核销入参。
 *
 * <p><b>按取货码核销，不按订单 id</b>：扫码枪扫出来的是码，不是 id。
 * 做成「先按码查 id、再按 id 核销」会多一次往返，且把一个原子动作拆成两步。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "现场核销")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallOrderVerifyDTO {

    @Schema(description = "取货码（扫码或手输，大小写与分隔符会被规整）")
    @NotBlank(message = "请扫描或输入取货码")
    @Size(max = 64, message = "取货码过长")
    private String code;
}
