package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后台可配的小块配置（V83）：界面水印、后台菜单排序。值是 JSON 整份覆盖。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_config")
public class SysConfig {
    @TableId(type = IdType.INPUT)
    private String configKey;
    private String configValue;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
