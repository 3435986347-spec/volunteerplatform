package com.hengde.honor.service;

import com.hengde.common.exception.BusinessException;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;

/**
 * 电子证书 PDF 渲染（iText）。
 *
 * <p><b>中文字体</b>：思源宋体（{@code fonts/NotoSerifSC-Regular.otf}，SIL OFL 1.1，许可证在同目录
 * {@code NotoSerifSC-OFL.txt}）<b>子集嵌入</b>到每张证书——证书是盖章文件，长什么样不能取决于阅读器手上有什么字体。
 * 此前用 STSong-Light 且不嵌入：Identity-H 下阅读器无从把编码对到汉字，套印内容全是乱码；
 * 换成 UniGB 预定义编码也只是交给阅读器替代字体，数字字宽不对、挤在一起，
 * 基本多文种平面以外的字还会在关闭文档时抛空指针（2026-10-30 前端反馈后实测）。
 * 字库里没有的字（如 {@code 𠮷}、emoji）显示成方框，不会让出证失败。</p>
 *
 * <p><b>本类不决定「该不该渲染」</b>——是否有可用样本、并发下谁来写，都由
 * {@link CertificateService} 把关。本类只负责「给定内容，产出 PDF 字节」。</p>
 *
 * @author hengde
 */
@Component
public class CertificatePdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private static final String FONT_RESOURCE = "fonts/NotoSerifSC-Regular.otf";

    /** 称呼那一行：四号。主标题在协会样本上（本类只补可变字段），字号随样本。 */
    private static final float SUBTITLE_SIZE = 14f;

    /** 正文与编号：小四。 */
    private static final float BODY_SIZE = 12f;

    /** 字间距：字与字之间多留四分之一个字（按各行字号算）。字号与字距都是需方 2026-10-30 看样后定的。 */
    private static final float LETTER_SPACING_EM = 0.25f;

    /** 字体只解析一次；缺文件或文件坏了就让应用启动失败，好过等志愿者点开证书才发现 */
    private final FontProgram fontProgram = loadFont();

    /**
     * 渲染所需的内容，全部由调用方查好后传入，避免本类反向依赖各领域服务。
     *
     * @param templatePdf 协会「电子样本」的 PDF 字节，<b>作为底图</b>。
     *                    公章就印在这份样本上——xlsx Row 36 要的「<b>盖章的</b>电子证书」由它保证。
     *                    <b>不得为 null</b>：没有样本就不该出证书（设计要点 ②：
     *                    退化成一张无章的证书比没有更糟）。
     */
    public record Content(String volunteerName,
                          String activityTitle,
                          String slotProjectName,
                          java.time.LocalDateTime slotStartTime,
                          java.time.LocalDateTime slotEndTime,
                          Integer serviceMinutes,
                          String certNo,
                          byte[] templatePdf) {
    }

    /**
     * 捐赠证书的内容（V3 结对批，xlsx Row 10「捐赠后需要自动生成证书」）。
     *
     * <p><b>为什么另起一个记录而不是把 {@link Content} 改通用</b>：活动证书那条路径已经在跑、也已被用例钉住，
     * 为了多一类证书去动它的字段，等于让一个已验证的渲染路径跟着变。两个记录各自表达各自那件事，
     * 共用的只有「套印在协会样本上」这一段。</p>
     *
     * @param templatePdf 协会「电子样本」的 PDF 字节，<b>作为底图</b>，不得为 null（理由同 {@link Content}）
     */
    public record DonationContent(String volunteerName,
                                  String statement,
                                  java.time.LocalDateTime time,
                                  String certNo,
                                  byte[] templatePdf) {
    }

    /**
     * 渲染捐赠证书：「您于 某日 {statement}。特发此证，以资鼓励。」套印到协会的捐赠样本上。
     *
     * <p>那半句话由捐赠域按类别给（{@code DonateCertificateQueryService}）：结对写「结对成功」、<b>不写金额</b>
     * （协会 2026-10-16 乙-1：证书在结对成功时出，不是付款后——系统那时并不知道钱有没有到账，
     * 印一张「已捐赠 N 元」的盖章证书是拿协会的公章为一件未经证实的事背书）；只有众筹捐款是钱到账后才出，才写金额。</p>
     */
    public byte[] render(DonationContent c) {
        if (c.templatePdf() == null || c.templatePdf().length == 0) {
            throw new BusinessException("证书电子样本内容为空，无法生成盖章证书");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfDocument pdf = openOnTemplate(c.templatePdf(), out);
             Document doc = new Document(pdf)) {

            PdfFont font = certificateFont();
            doc.setFont(font);
            doc.add(line(nullSafe(c.volunteerName()) + "　爱心人士：", SUBTITLE_SIZE).setMarginTop(120));

            StringBuilder body = new StringBuilder("　　您");
            if (c.time() != null) {
                body.append("于 ").append(c.time().format(DATE)).append(" ");
            }
            body.append(nullSafe(c.statement())).append("。特发此证，以资鼓励。");
            doc.add(line(body.toString(), BODY_SIZE).setMarginTop(20).setMultipliedLeading(1.8f));
            doc.add(certNoLine(nullSafe(c.certNo()), font).setMarginTop(40));

            doc.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("证书渲染失败：" + e.getMessage());
        }
    }

    /**
     * 把可变字段套印到协会样本上，产出成品证书。
     *
     * <p><b>为什么是「套印底图」而不是「按坐标摆字段」</b>：需求只写了「设置某个活动的电子样本」与
     * 「字段与盖章坐标」，<b>没有给出坐标的格式</b>。自行发明一套 layout schema 属于替协会做决定，
     * 是本项目此前反复吃亏的「设计超出需求」。而<b>把样本整页当底图</b>不需要任何坐标约定，
     * 却已经能满足「盖章」这条硬要求——章在样本上。待协会给出字段坐标后，
     * 只需在本方法里按 {@code layout} 摆放，底图逻辑不变。</p>
     *
     * @param c 证书内容
     * @return PDF 字节
     */
    public byte[] render(Content c) {
        if (c.templatePdf() == null || c.templatePdf().length == 0) {
            throw new BusinessException("证书电子样本内容为空，无法生成盖章证书");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfDocument pdf = openOnTemplate(c.templatePdf(), out);
             Document doc = new Document(pdf)) {

            PdfFont font = certificateFont();
            doc.setFont(font);

            // 底图（样本）通常已经画好标题与版式，这里只补【可变字段】，不再自绘标题，
            // 免得把协会样本上的标题盖住或与之重复。
            doc.add(line(nullSafe(c.volunteerName()) + "　志愿者：", SUBTITLE_SIZE).setMarginTop(120));

            StringBuilder body = new StringBuilder("　　您于 ");
            if (c.slotStartTime() != null) {
                body.append(c.slotStartTime().format(DATE));
            }
            body.append(" 参加了「").append(nullSafe(c.activityTitle())).append("」志愿服务活动");
            if (c.slotProjectName() != null && !c.slotProjectName().isBlank()) {
                // 场次（岗位）必须印上：一场活动一个证书，同一活动的两张证书只有这里不同
                body.append("（岗位：").append(c.slotProjectName()).append("）");
            }
            if (c.serviceMinutes() != null && c.serviceMinutes() > 0) {
                body.append("，服务时长 ").append(formatHours(c.serviceMinutes())).append(" 小时");
            }
            body.append("。特发此证，以资鼓励。");
            doc.add(line(body.toString(), BODY_SIZE).setMarginTop(20).setMultipliedLeading(1.8f));

            // 编号必须印：原型 P83 卡片上就有「证书编号」
            doc.add(certNoLine(nullSafe(c.certNo()), font).setMarginTop(40));

            doc.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("证书渲染失败：" + e.getMessage());
        }
    }

    /**
     * 以协会样本为底图打开文档：读入样本 PDF，逐页原样保留，可变字段写在其上。
     *
     * <p>用 {@link PdfDocument} 的「读 + 写」双流构造，iText 会把源文档整页（含公章图形）
     * 复制到输出，随后 {@link Document} 的内容叠加在同一页上。</p>
     *
     * <p>样本损坏/不是 PDF 时<b>直接失败</b>，不退回自绘版式——那样产出的就是一张无章证书，
     * 正是设计要点 ② 明确禁止的。</p>
     */
    private static PdfDocument openOnTemplate(byte[] templatePdf, ByteArrayOutputStream out) {
        try {
            return new PdfDocument(
                    new com.itextpdf.kernel.pdf.PdfReader(new java.io.ByteArrayInputStream(templatePdf)),
                    new PdfWriter(out));
        } catch (Exception e) {
            throw new BusinessException("证书电子样本不是有效的 PDF，无法套印：" + e.getMessage());
        }
    }

    private static Paragraph line(String text, float fontSize) {
        return new Paragraph(text).setFontSize(fontSize).setCharacterSpacing(fontSize * LETTER_SPACING_EM);
    }

    /**
     * 证书编号那行，数字逐位等距。这款字体的数字是比例宽度（{@code 1} 比其它窄一成多），直接排出来一眼能看出不齐；
     * iText 的版面用不上字体的等宽数字特性，所以给每一位单独配字距，补到同一个步长。
     */
    private static Paragraph certNoLine(String certNo, PdfFont font) {
        float step = font.getWidth('0', BODY_SIZE) + BODY_SIZE * LETTER_SPACING_EM;
        Paragraph p = line("证书编号：", BODY_SIZE);
        for (char digit : certNo.toCharArray()) {
            p.add(new Text(String.valueOf(digit)).setCharacterSpacing(step - font.getWidth(digit, BODY_SIZE)));
        }
        return p;
    }

    private static FontProgram loadFont() {
        try (InputStream in = CertificatePdfRenderer.class.getClassLoader().getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少证书字体 " + FONT_RESOURCE);
            }
            return FontProgramFactory.createFont(in.readAllBytes(), false);
        } catch (IOException e) {
            throw new IllegalStateException("读取证书字体失败：" + FONT_RESOURCE, e);
        }
    }

    /** PdfFont 绑定文档，每张证书现建。 */
    private PdfFont certificateFont() {
        return PdfFontFactory.createFont(fontProgram, PdfEncodings.IDENTITY_H,
                PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
    }

    /** 分钟转小时，保留一位小数，去掉多余的 .0。 */
    private static String formatHours(int minutes) {
        double h = minutes / 60.0;
        return h == Math.floor(h) ? String.valueOf((long) h) : String.format("%.1f", h);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
