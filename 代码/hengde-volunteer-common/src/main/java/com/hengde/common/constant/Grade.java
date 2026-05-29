package com.hengde.common.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 年级。code 为**有序编码**，每年 9 月的年级自动升级 job 直接取 {@link #next()} 即可（毕业封顶）。
 *
 * <p>入库存 {@code code}，实体字段直接用本枚举。</p>
 *
 * @author hengde
 */
@Getter
public enum Grade {

    GRADE_1(1, "一年级"),
    GRADE_2(2, "二年级"),
    GRADE_3(3, "三年级"),
    GRADE_4(4, "四年级"),
    GRADE_5(5, "五年级"),
    GRADE_6(6, "六年级"),
    GRADE_7(7, "七年级"),
    GRADE_8(8, "八年级"),
    GRADE_9(9, "九年级"),
    SENIOR_1(10, "高一"),
    SENIOR_2(11, "高二"),
    SENIOR_3(12, "高三"),
    COLLEGE_1(13, "大一"),
    COLLEGE_2(14, "大二"),
    COLLEGE_3(15, "大三"),
    COLLEGE_4(16, "大四"),
    COLLEGE_5(17, "大五"),
    GRADUATED(18, "毕业");

    @EnumValue
    private final Integer code;
    private final String label;

    Grade(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static Grade fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (Grade g : values()) {
            if (g.code.equals(code)) {
                return g;
            }
        }
        return null;
    }

    /** 升一级；已是「毕业」则保持不变。供年级自动升级 job 使用。 */
    public Grade next() {
        return this == GRADUATED ? GRADUATED : fromCode(this.code + 1);
    }
}
