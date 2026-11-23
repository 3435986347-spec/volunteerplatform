package com.hengde.auth.vo;

/**
 * 志愿者协议（注册前阅读）。
 *
 * @param version 协议版本号（注册时随手写签名一并入库）
 * @param text    协议正文
 * @author hengde
 */
public record AgreementVO(
        String version,
        String text
) {
}
