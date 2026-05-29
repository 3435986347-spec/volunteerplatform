package com.hengde.common.search;

/**
 * 全局搜索结果项（跨领域统一出参）。各领域 service 按关键词检索后返回此类型，
 * 由 api 聚合层合并成单一信息流（前端下滑加载、点项进详情）。
 *
 * @param type     结果类型，如 {@code activity} / {@code announcement}
 * @param id       业务主键
 * @param title    标题
 * @param summary  摘要（无则为 null）
 * @param imageUrl 封面图（无则为 null）
 */
public record SearchItemVO(
        String type,
        Long id,
        String title,
        String summary,
        String imageUrl
) {
}
