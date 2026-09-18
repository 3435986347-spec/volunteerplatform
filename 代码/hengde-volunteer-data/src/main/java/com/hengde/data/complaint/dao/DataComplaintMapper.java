package com.hengde.data.complaint.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.data.complaint.entity.DataComplaint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 投诉建议工单。
 *
 * @author hengde
 */
@Mapper
public interface DataComplaintMapper extends BaseMapper<DataComplaint> {
}
