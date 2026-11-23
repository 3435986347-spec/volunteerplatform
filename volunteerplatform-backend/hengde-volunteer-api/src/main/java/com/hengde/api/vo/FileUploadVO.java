package com.hengde.api.vo;

import lombok.Data;

/**
 * 通用上传出参：业务把 {@link #url} 存进对应表的图片/文件地址字段即可。
 *
 * @author hengde
 */
@Data
public class FileUploadVO {

    /** 可公开访问的文件 URL（OSS 未启用时为占位 URL，便于本地联调） */
    private String url;

    /** 原始文件名 */
    private String name;

    /** 文件大小（字节） */
    private long size;
}
