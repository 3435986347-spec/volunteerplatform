package com.hengde.system.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.system.entity.SysOperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志（只追加 + 按条件查 + 按保留期清理）。
 *
 * @author hengde
 */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /** 批量落库：日志是异步攒着写的，一条一条插会让高峰期的连接都耗在日志上。 */
    @Select("<script>SELECT 1</script>")
    default void unusedPlaceholder() {
    }

    @Select("<script>SELECT * FROM sys_operation_log WHERE 1 = 1"
            + "<if test='logType != null'> AND log_type = #{logType}</if>"
            + "<if test='actorType != null'> AND actor_type = #{actorType}</if>"
            + "<if test='actorId != null'> AND actor_id = #{actorId}</if>"
            + "<if test='keyword != null'> AND (action LIKE CONCAT('%', #{keyword}, '%') OR uri LIKE CONCAT('%', #{keyword}, '%')"
            + " OR actor_name LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "<if test='from != null'> AND create_time &gt;= #{from}</if>"
            + "<if test='to != null'> AND create_time &lt; #{to}</if>"
            + " ORDER BY id DESC LIMIT #{offset}, #{limit}</script>")
    List<SysOperationLog> search(@Param("logType") Integer logType, @Param("actorType") Integer actorType,
                                 @Param("actorId") Long actorId, @Param("keyword") String keyword,
                                 @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("offset") long offset, @Param("limit") long limit);

    @Select("<script>SELECT COUNT(*) FROM sys_operation_log WHERE 1 = 1"
            + "<if test='logType != null'> AND log_type = #{logType}</if>"
            + "<if test='actorType != null'> AND actor_type = #{actorType}</if>"
            + "<if test='actorId != null'> AND actor_id = #{actorId}</if>"
            + "<if test='keyword != null'> AND (action LIKE CONCAT('%', #{keyword}, '%') OR uri LIKE CONCAT('%', #{keyword}, '%')"
            + " OR actor_name LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "<if test='from != null'> AND create_time &gt;= #{from}</if>"
            + "<if test='to != null'> AND create_time &lt; #{to}</if></script>")
    long countSearch(@Param("logType") Integer logType, @Param("actorType") Integer actorType,
                     @Param("actorId") Long actorId, @Param("keyword") String keyword,
                     @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按保留期清理（Q8：默认 180 天）。
     *
     * <p><b>这是日志唯一的删除路径</b>——后台没有删除入口，否则「谁删了那条日志」又要靠日志来查。
     * 一次只删一批，避免一条 DELETE 锁住长时间。</p>
     */
    @Delete("DELETE FROM sys_operation_log WHERE create_time < #{before} LIMIT #{limit}")
    int purgeBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
