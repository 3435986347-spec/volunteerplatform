package com.hengde.donate.constant;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 可选的快递公司（编码为<b>快递100 的公司编码</b>）。
 *
 * <p><b>为什么让捐赠人从列表里选、而不是随手填名字</b>：查物流轨迹（Row 17「物流轨迹【有快递100的API】」）
 * 要的是编码不是名字；自由文本「顺丰」「顺丰速运」「SF」会查出三种结果。
 * 快递100 另有「智能识别单号」接口可以省掉这一步，但它是单独计费项（清单 ⑧），先不接。</p>
 *
 * <p>{@link #OTHER} 兜底：不在列表里的快递照样能登记，只是查不了轨迹——
 * 不能因为物流查询这个附加功能把捐赠本身挡在门外。</p>
 *
 * @author hengde
 */
public enum ExpressCompany {

    SF("shunfeng", "顺丰速运"),
    ZTO("zhongtong", "中通快递"),
    YTO("yuantong", "圆通速递"),
    YUNDA("yunda", "韵达快递"),
    STO("shentong", "申通快递"),
    JT("jtexpress", "极兔速递"),
    EMS("ems", "EMS"),
    POST("youzhengguonei", "邮政快递包裹"),
    JD("jd", "京东物流"),
    DEPPON("debangkuaidi", "德邦快递"),
    OTHER("other", "其他");

    private final String code;
    private final String label;

    ExpressCompany(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 能否向快递100 查询轨迹（{@link #OTHER} 不能）。 */
    public boolean trackable() {
        return this != OTHER;
    }

    public static Optional<ExpressCompany> ofCode(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }

    public static List<ExpressCompany> all() {
        return List.of(values());
    }
}
