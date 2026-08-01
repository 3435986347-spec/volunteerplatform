package com.hengde.honor.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证书电子样本展示行。
 *
 * @author hengde
 */
@Data
public class CertificateTemplateVO {

    private Long id;

    /** 作用域键：{@code activity:{id}} 或 {@code global} */
    private String scopeKey;

    /** 从 scopeKey 解析出的活动 id；全局样本为 null */
    private Long activityId;

    private String name;

    /** 样本文件的私有对象 key */
    private String fileKey;

    /** 字段与盖章坐标配置（JSON 文本） */
    private String layout;

    private Integer enabled;

    private LocalDateTime createTime;
}
