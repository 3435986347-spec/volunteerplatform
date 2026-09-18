package com.hengde.common.oss;

import java.util.Map;

/**
 * 直传签名：客户端拿 {@code method} + {@code uploadUrl} 带上 {@code headers}（一个都不能少、值不能改——它们参与了签名）把文件直接传到对象存储，
 * 传完把 {@code url} 交回业务接口。
 *
 * @param uploadUrl 限时有效的上传地址
 * @param method    HTTP 方法（PUT）
 * @param headers   必须原样携带的请求头
 * @param url       传完之后对象的访问地址（业务表存它）
 * @param objectKey 对象键
 * @author hengde
 */
public record PresignedUpload(String uploadUrl, String method, Map<String, String> headers, String url, String objectKey) {
}
