package com.hengde.common.qrcode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.hengde.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QrCodeUtil 验证：生成的 PNG data URL 能被 ZXing 解码回原内容。纯静态工具，无需 Spring/Docker。
 *
 * @author hengde
 */
class QrCodeUtilTest {

    private static final String PREFIX = "data:image/png;base64,";

    @Test
    void toPngDataUrl_encodesContent_decodableBack() throws Exception {
        String content = "hengde-activity-checkin:123456";
        String dataUrl = QrCodeUtil.toPngDataUrl(content, 360);

        assertTrue(dataUrl.startsWith(PREFIX), "应返回 PNG data URL");
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(PREFIX.length()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)));
        String decoded = new MultiFormatReader().decode(bitmap).getText();

        assertEquals(content, decoded, "二维码应能解码回原内容");
    }

    @Test
    void toPngDataUrl_blankContent_rejected() {
        assertThrows(BusinessException.class, () -> QrCodeUtil.toPngDataUrl("", 360));
    }
}
