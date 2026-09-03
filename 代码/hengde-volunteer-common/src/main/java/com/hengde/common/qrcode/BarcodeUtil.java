package com.hengde.common.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.hengde.common.exception.BusinessException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * 一维条码（Code128）生成工具（被动工具，无 bean/DI，与同包 {@link QrCodeUtil} 同一形态）。
 *
 * <p>包名叫 {@code qrcode} 是历史原因——二维码先来的；本包实际管的是「ZXing 渲染」这件事。</p>
 *
 * <p><b>为什么单列一个类而不是塞进 {@link QrCodeUtil}</b>：Code128 是<b>一维</b>码，
 * 宽高不等、纠错能力与二维码完全不同（几乎没有），调用方要传的参数与心智都不一样。
 * 当前调用方是自提取货码；捐书批的三级条码（物品 / 箱 / 运单）也走这里，
 * <b>不要在那边再抄一份编码代码</b>。</p>
 *
 * <p><b>Code128 只认 ASCII</b>（Code Set B 覆盖可打印 ASCII 32–126）。中文、emoji 传进来
 * ZXing 会抛异常；调用方应当先把内容限死在自己的字符集里
 * （如 {@code PickupCodeUtil} 的 32 字符字母表）。</p>
 *
 * @author hengde
 */
public final class BarcodeUtil {

    private BarcodeUtil() {
    }

    /** PNG data URL 前缀。 */
    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    /**
     * 生成 Code128 条码 PNG 的 base64 data URL，供前端 {@code <image>} 直接展示。
     *
     * @param content 条码内容，仅限可打印 ASCII（非空）
     * @param width   像素宽（建议 320~600；太窄会让条宽不足一像素而扫不出来）
     * @param height  像素高（建议 80~160）
     * @return {@code data:image/png;base64,...}
     */
    public static String toCode128PngDataUrl(String content, int width, int height) {
        if (content == null || content.isEmpty()) {
            throw new BusinessException("条码内容不能为空");
        }
        if (width <= 0 || height <= 0) {
            throw new BusinessException("条码尺寸必须大于0");
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.US_ASCII.name());
        hints.put(EncodeHintType.MARGIN, 8);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.CODE_128, width, height, hints);
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return PNG_DATA_URL_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new BusinessException("条码生成失败");
        }
    }
}
