package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件网盘的文件夹（V83，Row 71）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_folder")
public class SysFolder {
    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;
}
