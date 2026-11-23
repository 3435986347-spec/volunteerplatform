package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分兑换规则（Row 8 C「兑换规则（文字 + 图片）」）——<b>单行表，固定 id=1</b>。
 *
 * <p>{@code images} 在库里是<b>换行分隔</b>的 URL 串，出入参用 {@code List<String>}，
 * 转换收在 {@code MallExchangeRuleService} 一处。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_exchange_rule")
public class MallExchangeRule extends BaseEntity {

    /** 规则正文（富文本）。 */
    private String content;

    /** 配图 URL，换行分隔。 */
    private String images;

    /** 最后修改人 admin_user.id。 */
    private Long updateBy;
}
