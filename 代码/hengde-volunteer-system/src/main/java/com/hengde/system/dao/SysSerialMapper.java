package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysSerial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 十位编号的取号（Row 75）。
 *
 * @author hengde
 */
@Mapper
public interface SysSerialMapper extends BaseMapper<SysSerial> {

    /**
     * 取下一个号：<b>一条语句里读改写</b>，随后用 {@code LAST_INSERT_ID()} 取回本连接刚写的值。
     *
     * <p>「先 SELECT 当前值、再 UPDATE 加一」在并发下会发出两个一样的号——而编号是要印在文件上给人对的。
     * 段不存在时顺带建出来（{@code INSERT … ON DUPLICATE KEY UPDATE}），免得新功能上线还要先手工插一行。</p>
     */
    @Update("INSERT INTO sys_serial (segment, name, current_no, update_time) VALUES (#{segment}, #{name}, LAST_INSERT_ID(1), NOW()) "
            + "ON DUPLICATE KEY UPDATE current_no = LAST_INSERT_ID(current_no + 1), update_time = NOW()")
    int bumpNo(@Param("segment") String segment, @Param("name") String name);

    @Select("SELECT LAST_INSERT_ID()")
    long lastNo();
}
