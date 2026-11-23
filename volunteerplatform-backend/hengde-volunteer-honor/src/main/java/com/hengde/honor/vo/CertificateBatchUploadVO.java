package com.hengde.honor.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量上传 PDF 证书的结果。
 *
 * <p>需求要点：<b>逐条返回成功/失败明细，单条匹配不上不整批失败</b>——
 * 一次传几十上百个文件，因为一个手机号写错就全批回滚，运营只能反复试错。</p>
 *
 * @author hengde
 */
@Data
public class CertificateBatchUploadVO {

    private int total;
    private int succeeded;
    private int failed;

    private List<Row> rows = new ArrayList<>();

    /** 单个文件的处理结果。 */
    @Data
    public static class Row {

        /** 原始文件名 */
        private String fileName;

        /** 从文件名解析出的手机号（解析不出为 null） */
        private String phone;

        private boolean success;

        /** 成功时的证书 id */
        private Long certificateId;

        /** 失败原因（成功为 null） */
        private String error;
    }
}
