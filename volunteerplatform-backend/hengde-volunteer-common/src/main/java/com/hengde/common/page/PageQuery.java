package com.hengde.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.common.constant.CommonConstants;
import lombok.Data;

/**
 * 分页查询入参。
 *
 * <p>统一 URL 文档约定的分页参数：{@code page}（从 1 开始）、{@code size}（默认 10，最大 100）。
 * 各领域的列表查询 DTO 可 {@code extends PageQuery} 再加自己的筛选字段。</p>
 *
 * <p>{@link #getPage()}/{@link #getSize()} 返回的是**已纠正**的值（page 兜底为 1，
 * size 兜底为默认并钳到上限），调用方无需再校验。</p>
 *
 * @author hengde
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始 */
    private Integer page;

    /** 每页条数 */
    private Integer size;

    public Integer getPage() {
        return (page == null || page < 1) ? 1 : page;
    }

    public Integer getSize() {
        if (size == null || size < 1) {
            return CommonConstants.DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, CommonConstants.MAX_PAGE_SIZE);
    }

    /**
     * 转成 MyBatis-Plus 的分页对象，配合 Mapper 分页查询使用。
     *
     * @param <T> 记录类型
     * @return MyBatis-Plus Page
     */
    public <T> Page<T> toPage() {
        return Page.of(getPage(), getSize());
    }
}
