package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 十位编号的功能段与流水（V83，Row 75）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_serial")
public class SysSerial {
    @TableId(type = IdType.INPUT)
    private String segment;
    private String name;
    private Long currentNo;
    private LocalDateTime updateTime;
}
