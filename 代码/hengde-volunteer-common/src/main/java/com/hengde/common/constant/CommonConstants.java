package com.hengde.common.constant;

/**
 * 通用常量。
 *
 * <p>集中存放跨模块复用的「固定值」，比如鉴权请求头名、分页默认值，避免散落在各处的硬编码。</p>
 *
 * @author hengde
 */
public interface CommonConstants {

    /** 携带 token 的请求头名称（与 Sa-Token token-name 一致，无 Bearer 前缀） */
    String TOKEN_HEADER = "Authorization";

    /** 默认分页大小（每页条数） */
    Integer DEFAULT_PAGE_SIZE = 10;

    /** 最大分页大小，防止一次拉取过多数据拖垮服务 */
    Integer MAX_PAGE_SIZE = 100;
}
