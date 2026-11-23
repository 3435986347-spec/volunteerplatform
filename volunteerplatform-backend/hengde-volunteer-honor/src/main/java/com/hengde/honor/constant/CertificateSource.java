package com.hengde.honor.constant;

/**
 * 证书来源。
 *
 * <p>区分来源的实际用途：<b>只有系统生成（{@link #SYSTEM}）的证书参与懒渲染</b>——
 * 后台批量上传的证书文件本就已经在库里（协会线下已有的那些），
 * 若也走懒渲染会把上传的原件覆盖掉。</p>
 *
 * @author hengde
 */
public final class CertificateSource {

    private CertificateSource() {
    }

    /** 系统按电子样本渲染生成（xlsx Row 36「自动生成一个盖章的电子证书」） */
    public static final int SYSTEM = 1;

    /** 后台批量上传（Row 36 F 列第 ① 项「批量上传pdf证书」）——协会线下已有的证书直接入库 */
    public static final int ADMIN_UPLOAD = 2;

    /** i志愿导出上传（Row 37）——<b>第 4B 批</b>，本批不产生 */
    public static final int IVOL_UPLOAD = 3;
}
