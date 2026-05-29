package com.hengde.common.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 政治面貌。取值固定（来自需求文档）。
 *
 * <p>入库存 {@code code}，实体字段直接用本枚举。</p>
 *
 * @author hengde
 */
@Getter
public enum PoliticalStatus {

    MASSES(1, "群众"),
    LEAGUE_MEMBER(2, "共青团员"),
    CPC_PROBATIONARY(3, "中共预备党员"),
    CPC_MEMBER(4, "中共党员"),
    DEMOCRATIC_PARTY(5, "民主党派");

    @EnumValue
    private final Integer code;
    private final String label;

    PoliticalStatus(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static PoliticalStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PoliticalStatus p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        return null;
    }
}
