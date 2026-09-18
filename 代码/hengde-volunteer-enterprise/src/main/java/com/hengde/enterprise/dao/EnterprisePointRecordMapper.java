package com.hengde.enterprise.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.enterprise.entity.EnterprisePointRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 爱心企业积分流水（V79）。
 *
 * @author hengde
 */
public interface EnterprisePointRecordMapper extends BaseMapper<EnterprisePointRecord> {

    @Select("SELECT COALESCE(SUM(change_amount), 0) FROM enterprise_point_record WHERE enterprise_id = #{enterpriseId}")
    long sumBalance(@Param("enterpriseId") Long enterpriseId);

    /** 撞 {@code uk_request_id} 之后取回已有那一笔做载荷复核：当前读 + 共享锁（同志愿者账本：用 FOR UPDATE 多个输家会互等成死锁）。 */
    @Select("SELECT * FROM enterprise_point_record WHERE request_id = #{requestId} FOR SHARE")
    EnterprisePointRecord selectByRequestIdForShare(@Param("requestId") String requestId);

    /** 这批兑换单里已经入过账的（一次查完）。 */
    @Select({"<script>SELECT source_id FROM enterprise_point_record WHERE source_type = 1 AND source_id IN ",
            "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<Long> selectCreditedOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
