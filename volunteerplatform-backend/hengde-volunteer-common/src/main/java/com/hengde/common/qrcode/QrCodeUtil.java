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
 * 二维码生成工具（被动工具，无 bean/DI，仿 {@code PasswordUtil}）。
 *
 * <p>用 ZXing 把文本内容编码为二维码 PNG，并以 base64 data URL 形式返回，供前端 {@code <image>} 直接展示
 * （小程序/浏览器均支持 {@code data:image/png;base64,...}）。当前用于活动签到二维码——负责人端展示、
 * 志愿者端扫码，内容由调用方拼装（如 {@code hengde-activity-checkin:{id}}）。</p>
 *
 * @author hengde
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    /** PNG data URL 前缀。 */
    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    /**
     * 生成二维码 PNG 的 base64 data URL。
     *
     * @param content 二维码内容（非空）
     * @param size    边长像素（建议 240~480）
     * @return {@code data:image/png;base64,...}
     */
    public static String toPngDataUrl(String content, int size) {
        if (content == null || content.isEmpty()) {
            throw new BusinessException("二维码内容不能为空");
        }
        if (size <= 0) {
            throw new BusinessException("二维码尺寸必须大于0");
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, 1);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return PNG_DATA_URL_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new BusinessException("二维码生成失败");
        }
    }
}
