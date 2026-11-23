package com.hengde.donate.dao;

/**
 * 占位说明：物资流转各表的 Mapper 分别在同包的独立接口里（MyBatis 的 {@code @MapperScan} 按接口扫描）。
 * 本类不承载任何逻辑，只为把「哪些 Mapper 有手写 SQL、为什么」集中写一处：
 *
 * <ul>
 *   <li>{@link DonateBoxMapper#selectByIdForShare} + {@link DonateItemMapper#packIntoBox}：装箱<b>先对箱子取 S、
 *       再对物资做单表 CAS</b>——流水线上碰箱子的动作一律「先箱后物」，与送达同序，不成环
 *       （早先的 {@code UPDATE ... JOIN} 写法实际是「先物后箱」，压测里与送达互等成死锁）；</li>
 *   <li>{@link DonateItemMapper#selectByIdForShare}：CAS 失败后的复核必须是当前读，否则读到的是 CAS 之前的快照；</li>
 *   <li>{@link DonateItemMapper#search}：Row 17 F 的 10 维搜索，分页与导出共用一段 SQL；</li>
 *   <li>{@link DonateShipmentMapper#selectTrackJson}：MEDIUMTEXT 的完整轨迹只在需要时单独读。</li>
 * </ul>
 *
 * @author hengde
 */
final class DonateSimpleMappers {

    private DonateSimpleMappers() {
    }
}
