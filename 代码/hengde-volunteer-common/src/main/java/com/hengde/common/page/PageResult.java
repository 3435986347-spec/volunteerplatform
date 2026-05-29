package com.hengde.common.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

/**
 * 分页查询结果。
 *
 * <p>所有列表接口统一用 {@code Result<PageResult<XxxVO>>} 返回，前端拿到的分页结构一致。
 * 最常见的用法是从 MyBatis-Plus 的分页结果转换：{@code PageResult.of(mpPage)}。</p>
 *
 * @param <T> 记录类型
 * @author hengde
 */
@Data
public class PageResult<T> {

    /** 当前页记录 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private long page;

    /** 每页条数 */
    private long size;

    /** 总页数 */
    private long pages;

    /**
     * 从 MyBatis-Plus 分页结果转换。
     *
     * @param page MyBatis-Plus 分页对象
     * @param <T>  记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(page.getRecords());
        result.setTotal(page.getTotal());
        result.setPage(page.getCurrent());
        result.setSize(page.getSize());
        result.setPages(page.getPages());
        return result;
    }

    /**
     * 用原始数据构造（非 MyBatis-Plus 来源时用，如内存分页/聚合查询）。
     *
     * @param records 当前页记录
     * @param total   总数
     * @param page    页码
     * @param size    每页条数
     * @param <T>     记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size == 0 ? 0 : (total + size - 1) / size);
        return result;
    }
}
