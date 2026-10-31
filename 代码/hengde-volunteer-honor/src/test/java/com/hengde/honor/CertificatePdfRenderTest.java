package com.hengde.honor;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.service.CertificatePdfRenderer;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证书 PDF 的字体与版面（2026-10-30 前端反馈：套印的中文是乱码；换编码后数字又挤在一起）。
 *
 * <p>接口 200、文件非空、能抽出字都证明不了「显示正确」——当初的乱码件这三条全过。这里断言的是<b>文件本身</b>：
 * 字体真的嵌进去了（不靠阅读器替代），字宽来自这款字体（编号逐位等距、互不重叠）。</p>
 *
 * <p>注解与 {@code CertificateServiceTest} 一致，好复用同一个测试上下文。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class CertificatePdfRenderTest {

    private static final String CERT_NO = "20260804142638885148";
    private static final String LONG_NAME = "欧阳娜娜娜娜·买买提艾力";
    private static final String LONG_TITLE = "雷州市恒德爱心公益协会2026年秋季湖光岩环保清洁志愿服务长名称活动";

    @Autowired
    private CertificatePdfRenderer renderer;

    @Test
    void activityCertificate_embedsItsFont_andReadsBackExactly() throws Exception {
        byte[] pdf = renderer.render(activity(LONG_NAME, LONG_TITLE, CERT_NO));

        assertEmbeddedSongti(pdf);
        String text = textOf(pdf);
        assertReads(text, LONG_NAME + "志愿者：");
        assertReads(text, LONG_TITLE);
        assertReads(text, "（岗位：引导）");
        assertReads(text, "服务时长2.5小时。特发此证，以资鼓励。");
        assertReads(text, "证书编号：" + CERT_NO);
    }

    @Test
    void donationCertificate_embedsItsFont_andReadsBackExactly() throws Exception {
        byte[] pdf = renderer.render(new CertificatePdfRenderer.DonationContent(LONG_NAME, "向「" + LONG_TITLE + "」捐款 128.50 元",
                LocalDateTime.of(2026, 10, 30, 9, 0), CERT_NO, blankTemplate()));

        assertEmbeddedSongti(pdf);
        String text = textOf(pdf);
        assertReads(text, LONG_TITLE);
        assertReads(text, "捐款128.50元");
        assertReads(text, "证书编号：" + CERT_NO);
    }

    /** 不嵌字体时字宽和阅读器替代上来的字形对不上，编号就挤在一起、间距不一——逐位量。 */
    @Test
    void certNumberDigits_areEvenlySpaced_andDoNotOverlap() throws Exception {
        List<float[]> digits = certNumberGlyphs(renderer.render(activity(LONG_NAME, LONG_TITLE, CERT_NO)));
        assertEquals(CERT_NO.length(), digits.size());
        float firstAdvance = digits.get(1)[0] - digits.get(0)[0];
        assertTrue(firstAdvance > 8f, "小四的数字加上字距不该窄到这个程度：" + firstAdvance);
        for (int i = 0; i + 1 < digits.size(); i++) {
            float advance = digits.get(i + 1)[0] - digits.get(i)[0];
            assertEquals(firstAdvance, advance, 0.05f, "第 " + i + " 位与下一位的间距不一致");
            assertTrue(digits.get(i)[1] <= digits.get(i + 1)[0] + 0.05f, "第 " + i + " 位与下一位重叠");
        }
    }

    /** 字间距：需方看样后定的「多四分之一个字」。编号那行是小四（12 磅），「证书编号」四个字的步长应是 12 × 1.25。 */
    @Test
    void characters_areAQuarterCharacterApart() throws Exception {
        List<float[]> all = glyphsMatching(renderer.render(activity(LONG_NAME, LONG_TITLE, CERT_NO)), "[证书编号]");
        List<float[]> label = all.subList(all.size() - 4, all.size()); // 正文「特发此证」也匹配，编号那行在最后
        for (int i = 0; i + 1 < label.size(); i++) {
            assertEquals(12f * 1.25f, label.get(i + 1)[0] - label.get(i)[0], 0.05f);
        }
    }

    /** 字库里没有的字显示成方框；不能像预定义编码那样在关闭文档时抛空指针，让这个人的证书永远出不来。 */
    @Test
    void charactersMissingFromTheFont_doNotFailTheCertificate() throws Exception {
        byte[] pdf = renderer.render(activity("张𠮷💧", LONG_TITLE, CERT_NO));

        assertEmbeddedSongti(pdf);
        assertReads(textOf(pdf), "证书编号：" + CERT_NO);
    }

    /** 不同证书同时渲染：共用同一份解析好的字体，各自的 PDF 不能串字、不能缺字形。 */
    @Test
    void concurrentRenders_keepTheirOwnText() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String title = "并发第" + i + "场活动";
                results.add(pool.submit(() -> {
                    byte[] pdf = renderer.render(activity("志愿者", title, CERT_NO));
                    assertEmbeddedSongti(pdf);
                    assertReads(textOf(pdf), title);
                    return title;
                }));
            }
            for (Future<String> f : results) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static CertificatePdfRenderer.Content activity(String name, String title, String certNo) {
        return new CertificatePdfRenderer.Content(name, title, "引导", LocalDateTime.of(2026, 10, 30, 9, 0),
                LocalDateTime.of(2026, 10, 30, 11, 30), 150, certNo, blankTemplate());
    }

    private static void assertEmbeddedSongti(byte[] pdf) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            PdfDictionary fonts = doc.getPage(1).getResources().getResource(PdfName.Font);
            Set<PdfName> names = fonts.keySet();
            assertFalse(names.isEmpty(), "页面上没有字体");
            for (PdfName name : names) {
                PdfDictionary font = fonts.getAsDictionary(name);
                assertTrue(font.getAsName(PdfName.BaseFont).getValue().contains("NotoSerifSC"), font.toString());
                assertTrue(font.containsKey(PdfName.ToUnicode), "没有 ToUnicode，复制和搜索出来的字不对");
                PdfDictionary descriptor = font.getAsArray(PdfName.DescendantFonts).getAsDictionary(0)
                        .getAsDictionary(PdfName.FontDescriptor);
                assertTrue(descriptor.containsKey(PdfName.FontFile3) || descriptor.containsKey(PdfName.FontFile2),
                        "字体没有嵌入，显示就看阅读器手上有什么：" + font);
            }
        }
    }

    /** 抽全文并去掉各种空白：长句会折行，字距也可能被抽成空格，比对时不该受这些影响。 */
    private static String textOf(byte[] pdf) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            return PdfTextExtractor.getTextFromPage(doc.getPage(1)).replaceAll("[\\s\\p{Z}]", "");
        }
    }

    /**
     * 比对抽出来的字。思源宋体里「力」「月」「长」这些字与同形的部首码位共用一个字形，iText 写 ToUnicode 时
     * 记的是部首那个码位：显示完全一样（同一字形），只是从 PDF 里复制、搜索得到的是部首码位。所以两边都归一后再比。
     */
    private static void assertReads(String text, String expected) {
        assertTrue(radicalsFolded(text).contains(radicalsFolded(expected).replaceAll("\\p{Z}", "")), expected + " 不在：" + text);
    }

    private static String radicalsFolded(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replace('⻓', '长');
    }

    /** 证书编号每一位的 {起点 x, 终点 x}。 */
    private static List<float[]> certNumberGlyphs(byte[] pdf) throws Exception {
        List<float[]> digits = glyphsMatching(pdf, "\\d");
        // 正文里也有日期、时长等数字，编号是页面上最后一串
        return digits.subList(digits.size() - CERT_NO.length(), digits.size());
    }

    /** 页面上匹配 {@code regex} 的单字，按绘制顺序给出 {起点 x, 终点 x}。 */
    private static List<float[]> glyphsMatching(byte[] pdf, String regex) throws Exception {
        List<float[]> glyphs = new ArrayList<>();
        IEventListener listener = new IEventListener() {
            @Override
            public void eventOccurred(IEventData data, EventType type) {
                if (type != EventType.RENDER_TEXT) {
                    return;
                }
                for (TextRenderInfo glyph : ((TextRenderInfo) data).getCharacterRenderInfos()) {
                    if (glyph.getText().matches(regex)) {
                        glyphs.add(new float[]{glyph.getBaseline().getStartPoint().get(0),
                                glyph.getBaseline().getEndPoint().get(0)});
                    }
                }
            }

            @Override
            public Set<EventType> getSupportedEvents() {
                return Set.of(EventType.RENDER_TEXT);
            }
        };
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            new PdfCanvasProcessor(listener).processPageContent(doc.getPage(1));
        }
        return glyphs;
    }

    private static byte[] blankTemplate() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfDocument pdf = new PdfDocument(new PdfWriter(out))) {
            pdf.addNewPage();
            pdf.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("造样本 PDF 失败", e);
        }
    }
}
