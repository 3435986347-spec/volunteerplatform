package com.hengde.honor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 受理申诉。
 *
 * @author hengde
 */
@Data
public class AppealHandleDTO {

    /** true = 申诉成立（撤销处置并冲正积分）；false = 驳回，维持原处罚 */
    @NotNull(message = "请给出受理结论")
    private Boolean upheld;

    /**
     * 结论说明。
     *
     * <p><b>成立与驳回都必填</b>：驳回时不写理由，志愿者只会看到「申诉失败」四个字；
     * 成立时不写，日后也说不清是认定处罚错了还是酌情撤销。</p>
     */
    @NotBlank(message = "受理结论说明不能为空")
    @Size(max = 512, message = "结论说明不超过 512 字")
    private String result;
}
