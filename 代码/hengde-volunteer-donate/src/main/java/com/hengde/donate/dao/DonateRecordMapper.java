package com.hengde.donate.dao;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的捐赠记录」（Row 33，V3 收尾批）：众筹的<b>捐款</b>与<b>捐物</b>合成一条时间线。
 *
 * <p>两种记录在两张表（{@code donate_donation} / {@code donate_shipment}），分页必须在库里合并排序——
 * 各查一页再在 Java 里拼，第 2 页就对不上了。所以是一条 {@code UNION ALL}，外层统一排序、分页；
 * 各支走各自的 {@code idx_volunteer} / {@code idx_donor}。</p>
 *
 * <p>{@code LIMIT} 手写而不经分页拦截器：拦截器只在 api 装配，领域模块测试上下文没有它，
 * 手写才能让用例与生产走同一条 SQL。</p>
 *
 * @author hengde
 */
@Mapper
public interface DonateRecordMapper {

    /** kind：0 全部 / 1 捐款 / 2 捐物——不要的那一支整段短路掉。 */
    String BODY = "FROM ("
            + "SELECT 1 AS kind, d.id AS refId, d.project_id AS projectId, d.project_title AS projectTitle, "
            + "  d.amount AS amount, d.status AS status, NULL AS expressCompany, NULL AS expressNo, d.create_time AS recordTime "
            + "  FROM donate_donation d "
            + "  WHERE d.volunteer_id = #{volunteerId} AND d.biz_type = #{bizMoney} AND d.is_deleted = 0 AND #{kind} IN (0, 1) "
            + "UNION ALL "
            + "SELECT 2, s.id, s.biz_id, NULL, NULL, s.status, s.express_company, s.express_no, s.ship_time "
            + "  FROM donate_shipment s "
            + "  WHERE s.donor_volunteer_id = #{volunteerId} AND s.biz_type = #{bizGoods} AND s.is_deleted = 0 AND #{kind} IN (0, 2)"
            + ") t";

    /** 一页记录：时间倒序；同一时刻按 kind、再按 id 倒序——定序必须确定，否则翻页会重复或漏行。 */
    @Select("SELECT kind, refId, projectId, projectTitle, amount, status, expressCompany, expressNo, recordTime "
            + BODY + " ORDER BY recordTime DESC, kind ASC, refId DESC LIMIT #{offset}, #{size}")
    List<Row> selectMine(@Param("volunteerId") Long volunteerId, @Param("kind") int kind,
                         @Param("bizMoney") int bizMoney, @Param("bizGoods") int bizGoods,
                         @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) " + BODY)
    long countMine(@Param("volunteerId") Long volunteerId, @Param("kind") int kind,
                   @Param("bizMoney") int bizMoney, @Param("bizGoods") int bizGoods);

    /** 一行合并记录（列名即字段名，MyBatis 按别名自动映射）。 */
    @Data
    class Row {
        private Integer kind;
        private Long refId;
        private Long projectId;
        private String projectTitle;
        private BigDecimal amount;
        private Integer status;
        private String expressCompany;
        private String expressNo;
        private LocalDateTime recordTime;
    }
}
