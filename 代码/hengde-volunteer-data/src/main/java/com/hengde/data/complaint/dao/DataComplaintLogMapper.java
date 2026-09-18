package com.hengde.data.complaint.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.data.complaint.entity.DataComplaintLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 投诉建议处理进度。
 *
 * @author hengde
 */
@Mapper
public interface DataComplaintLogMapper extends BaseMapper<DataComplaintLog> {
}
