package com.hengde.common.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 性别。可由身份证号自动解析后存入。
 *
 * <p>入库存 {@code code}（由 MyBatis-Plus 的 {@link EnumValue} 标注决定），实体字段直接用本枚举。</p>
 *
 * @author hengde
 */
@Getter
public enum Gender {

    UNKNOWN(0, "未知"),
    MALE(1, "男"),
    FEMALE(2, "女");

    @EnumValue
    private final Integer code;
    private final String label;

    Gender(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static Gender fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (Gender g : values()) {
            if (g.code.equals(code)) {
                return g;
            }
        }
        return UNKNOWN;
    }
}
