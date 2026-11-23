package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateDonation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 捐款记录（V59）。
 *
 * @author hengde
 */
@Mapper
public interface DonateDonationMapper extends BaseMapper<DonateDonation> {

    /**
     * 各项目的捐款人数：已到账捐款的<b>去重</b>捐款人数，现算（不在项目表上存列，理由同结对的参加人数）。
     */
    @Select("<script>SELECT project_id AS projectId, COUNT(DISTINCT volunteer_id) AS cnt FROM donate_donation "
            + "WHERE biz_type = #{bizType} AND status = #{paid} AND is_deleted = 0 AND project_id IN "
            + "<foreach collection='projectIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY project_id</script>")
    List<Map<String, Object>> countDonors(@Param("bizType") int bizType,
                                          @Param("projectIds") Collection<Long> projectIds,
                                          @Param("paid") int paid);

    /**
     * 按主键取当前读 + 排他锁（退款：读完要改这一行，且与并发的回写串行化）。
     */
    @Select("SELECT * FROM donate_donation WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    DonateDonation selectByIdForUpdate(@Param("id") Long id);
}
