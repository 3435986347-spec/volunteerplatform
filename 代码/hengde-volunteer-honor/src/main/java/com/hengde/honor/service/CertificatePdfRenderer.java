package com.hengde.honor.service;

import com.hengde.common.exception.BusinessException;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

/**
 * 电子证书 PDF 渲染（iText）。
 *
 * <p><b>中文字体</b>：iText 默认不含中文字体，直接写中文会得到空白或乱码。这里用
 * {@code font-asian} 提供的 Adobe CJK 字体包 {@code STSong-Light + UniGB-UCS2-H}，
 * 好处是<b>无需随包分发字体文件、也不涉及字体授权</b>；代价是字形单一（宋体）。
 * 若日后协会要求特定字体，改为嵌入字体文件即可，届时须一并处理授权与体积。</p>
 *
 * <p><b>本类不决定「该不该渲染」</b>——是否有可用样本、并发下谁来写，都由
 * {@link CertificateService} 把关。本类只负责「给定内容，产出 PDF 字节」。</p>
 *
 * @author hengde
 */
@Component
public class CertificatePdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");

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

            PdfFont font = PdfFontFactory.createFont("STSong-Light", PdfEncodings.IDENTITY_H);
            doc.setFont(font);

            // 底图（样本）通常已经画好标题与版式，这里只补【可变字段】，不再自绘标题，
            // 免得把协会样本上的标题盖住或与之重复。
            doc.add(new Paragraph(nullSafe(c.volunteerName()) + "　志愿者：")
                    .setFontSize(15).setMarginTop(120));

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
            doc.add(new Paragraph(body.toString()).setFontSize(14).setMarginTop(20).setMultipliedLeading(1.8f));

            // 编号必须印：原型 P83 卡片上就有「证书编号」
            doc.add(new Paragraph("证书编号：" + nullSafe(c.certNo()))
                    .setFontSize(11).setMarginTop(40));

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

    /** 分钟转小时，保留一位小数，去掉多余的 .0。 */
    private static String formatHours(int minutes) {
        double h = minutes / 60.0;
        return h == Math.floor(h) ? String.valueOf((long) h) : String.format("%.1f", h);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
