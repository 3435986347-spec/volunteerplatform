package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作日志 / 页面访问（V83，Row 62）。<b>只追加</b>：没有修改与删除入口，唯一的删除路径是按保留期的定时清理。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_operation_log")
public class SysOperationLog {
    private Long id;
    private Integer logType;
    private Integer actorType;
    private Long actorId;
    /** 姓名与部门存快照：账号日后改名、换部门甚至被删，日志仍要说得清当时是谁 */
    private String actorName;
    private String department;
    private String action;
    private String method;
    private String uri;
    private String query;
    private String ip;
    private String userAgent;
    private Integer success;
    private String errorMsg;
    private Integer costMs;
    private LocalDateTime createTime;
}
