package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hengde.donate.dto.DonateItemQuery;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.vo.DonateItemRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DonateItem Mapper。
 *
 * @author hengde
 */
public interface DonateItemMapper extends BaseMapper<DonateItem> {

    /**
     * 扫专属码装箱（单表 CAS，只锁这一件物资）。影响行数 1=装进去了，0=没装。
     *
     * <p><b>调用方必须先对箱子取共享锁</b>（{@code DonateBoxMapper.selectByIdForShare}）并确认它仍在装箱中——
     * 箱子的状态由那把锁钉住，这里只折进物资自己会被并发改变的条件（合格、未装箱、同一活动）。
     * 两台手机同时把同一件物资装进两只箱子，只有一个拿到 1。</p>
     *
     * <p><b>不再写成 {@code UPDATE donate_item i JOIN donate_box b}</b>：那样实际加锁顺序是「先物资、后箱子」，
     * 与送达「先箱子、后物资」相反，压测里稳定死锁（见 {@code DonateBoxMapper.selectByIdForShare}）。
     * 与库存那条 D7(b) 的区别在于：那里读的与改的是<b>同一行</b>（先 S 后 X 是锁升级）；
     * 这里箱子只读、物资只改，是<b>两行</b>——先对只读的那行取 S、再对要改的那行取 X，是正确形态。</p>
     *
     * <p>{@code is_deleted = 0} 显式写：{@code @TableLogic} 不管手写 SQL。</p>
     */
    @Update("UPDATE donate_item SET status = #{packed}, box_id = #{boxId}, update_time = #{now} "
            + "WHERE exclusive_code = #{code} AND status = #{qualified} AND box_id IS NULL AND is_deleted = 0 "
            + "AND biz_type = #{bizType} AND biz_id = #{bizId}")
    int packIntoBox(@Param("boxId") Long boxId, @Param("code") String code, @Param("now") LocalDateTime now,
                    @Param("bizType") int bizType, @Param("bizId") Long bizId,
                    @Param("qualified") int qualified, @Param("packed") int packed);

    /** 扫专属码出箱（装错了箱）。调用方同样先对箱子取共享锁并确认仍在装箱中。 */
    @Update("UPDATE donate_item SET status = #{qualified}, box_id = NULL, update_time = #{now} "
            + "WHERE exclusive_code = #{code} AND box_id = #{boxId} AND status = #{packed} AND is_deleted = 0")
    int unpackFromBox(@Param("boxId") Long boxId, @Param("code") String code, @Param("now") LocalDateTime now,
                      @Param("qualified") int qualified, @Param("packed") int packed);

    /**
     * 当前读（共享锁）取一件物资。用于 CAS 失败后的复核：RR 下事务里第一条普通 SELECT 就把读视图定死了，
     * 普通查询看不见刚刚赢了 CAS 的那个事务提交的结果——生成专属码的并发用例正是这样撞出了
     * 「只有核对合格的物资才能生成专属码（当前：合格）」这句自相矛盾的话。只读比对，所以取 S。
     */
    @Select("SELECT * FROM donate_item WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    DonateItem selectByIdForShare(@Param("id") Long id);

    /**
     * 某条业务记录下「还活着」的物资数：没被判不合格、没退回、没取消的（在途 / 待核对 / 合格 / 装箱 / 送达）。
     * <b>当前读 + 共享锁</b>，范围落在 {@code idx_biz_status} 上、连间隙一起锁，挡住并发插入的新物资。
     */
    @Select("SELECT COUNT(*) FROM donate_item WHERE biz_type = #{bizType} AND biz_id = #{bizId} AND is_deleted = 0 "
            + "AND status NOT IN (#{rejected}, #{returned}, #{cancelled}) FOR SHARE")
    long countLiveGoodsForShare(@Param("bizType") int bizType, @Param("bizId") Long bizId,
                                @Param("rejected") int rejected, @Param("returned") int returned,
                                @Param("cancelled") int cancelled);

    /**
     * Row 17 F 的 10 维搜索（箱子码、专属条形码、物品条形码、捐赠人名字、捐赠人电话、捐赠人单位、
     * 物资名称、物资类型、快递单号、目前进度），另加来源筛选。<b>分页与导出共用这一段 SQL</b>，
     * 否则「列表里搜得到、导出来却没有」迟早发生。
     *
     * <p>捐赠人电话是密文，不能 LIKE：服务层先经 auth 换成志愿者 id 再以 {@code donorVolunteerId} 精确匹配。
     * 捐赠人名字用运单上的<b>登记时快照</b> {@code donor_name}——那正是标签与收件人上写的名字。</p>
     */
    String SEARCH_BODY = "FROM donate_item i JOIN donate_shipment s ON s.id = i.shipment_id "
            + "LEFT JOIN donate_box b ON b.id = i.box_id "
            + "LEFT JOIN donate_campaign c ON c.id = i.biz_id AND i.biz_type = 1 "
            + "WHERE i.is_deleted = 0 AND s.is_deleted = 0 "
            + "<if test='q.bizType != null'> AND i.biz_type = #{q.bizType}</if>"
            + "<if test='q.bizId != null'> AND i.biz_id = #{q.bizId}</if>"
            + "<if test='q.boxCode != null and q.boxCode != \"\"'> AND b.box_code = #{q.boxCode}</if>"
            + "<if test='q.exclusiveCode != null and q.exclusiveCode != \"\"'> AND i.exclusive_code = #{q.exclusiveCode}</if>"
            + "<if test='q.catalogBarcode != null and q.catalogBarcode != \"\"'> AND i.catalog_barcode = #{q.catalogBarcode}</if>"
            + "<if test='q.donorName != null and q.donorName != \"\"'> AND s.donor_name LIKE CONCAT('%', #{q.donorName}, '%')</if>"
            + "<if test='q.donorVolunteerId != null'> AND i.donor_volunteer_id = #{q.donorVolunteerId}</if>"
            + "<if test='q.donorOrg != null and q.donorOrg != \"\"'> AND s.donor_org LIKE CONCAT('%', #{q.donorOrg}, '%')</if>"
            + "<if test='q.itemName != null and q.itemName != \"\"'> AND i.name LIKE CONCAT('%', #{q.itemName}, '%')</if>"
            + "<if test='q.itemType != null'> AND i.item_type = #{q.itemType}</if>"
            + "<if test='q.expressNo != null and q.expressNo != \"\"'> AND s.express_no = #{q.expressNo}</if>"
            + "<if test='q.status != null'> AND i.status = #{q.status}</if>"
            + "<if test='q.recipientOrgId != null'> AND i.recipient_org_id = #{q.recipientOrgId}</if>";

    String SEARCH_COLUMNS = "SELECT i.id, i.shipment_id, i.biz_type, i.biz_id, i.donor_volunteer_id, i.name, "
            + "i.item_type, i.quantity, i.catalog_barcode, i.exclusive_code, i.status, i.check_remark, i.box_id, "
            + "i.recipient_org_id, i.recipient_org_name, i.deliver_time, i.borrow_count, i.create_time, "
            + "s.donor_name, s.donor_org, s.express_company, s.express_no, b.box_code, c.title AS campaign_title ";

    @Select("<script>" + SEARCH_COLUMNS + SEARCH_BODY + " ORDER BY i.id DESC</script>")
    IPage<DonateItemRow> search(IPage<DonateItemRow> page, @Param("q") DonateItemQuery q);

    /** 导出用：同一段条件，不分页、带上限（上限由服务层给，超了就让人缩小范围，不静默截断）。 */
    @Select("<script>" + SEARCH_COLUMNS + SEARCH_BODY + " ORDER BY i.id DESC LIMIT #{limit}</script>")
    List<DonateItemRow> searchForExport(@Param("q") DonateItemQuery q, @Param("limit") int limit);
}
