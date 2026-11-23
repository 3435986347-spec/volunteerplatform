/**
 * 积分商城与捐赠域（V3）：Row 8 积分兑换、Row 17 公益捐书、Row 12 圆梦微心愿、
 * Row 10 助学助困、Row 16 项目众筹，以及 Row 33~35/38 的「我的」聚合视图。
 *
 * <p><b>命名分两套，各有各的理由</b>（V3规划 承重条款 2）：</p>
 * <ul>
 *   <li><b>URL 与权限点按模块</b>——{@code /v/donate/**}、{@code /a/donate/**}、{@code donate:*}；</li>
 *   <li><b>表名按聚合根概念</b>——商城全 {@code mall_}、物资流转全 {@code donate_}。
 *       这与既有实践一致（{@code point_record} 在 activity、{@code manager_application} 在
 *       organization，都跟着概念而非模块走）。
 *       ⚠️ 捐书条码库叫 {@code donate_barcode_catalog} 而非 {@code goods_barcode}——
 *       后者在一个有 {@code mall_goods} 的库里会被当成商城的商品表。</li>
 * </ul>
 *
 * <p><b>积分只经 activity 的 {@code PointService} 入账</b>，不直连 {@code point_record}；
 * 兑换用来源码 {@code PointSourceType.EXCHANGE}，退分走系统保留前缀
 * {@code sys:mall-refund:} 的 {@code requestId}。
 * <b>退分复用 EXCHANGE 的理由是口径不是键</b>：若另分配一个非消费类来源码，
 * {@code totalSpent} 与 {@code totalEarned} 会同时算错，排行榜跟着错。</p>
 *
 * <p><b>跨域一律走窄接口</b>：志愿者走 {@code VolunteerQueryService}、收付走 trade，
 * 不碰对方 Mapper。</p>
 *
 * @author hengde
 */
package com.hengde.donate;
