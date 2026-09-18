package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件分享链接（V83，Row 71「可以分享、下载」+「后台可以设置是否登录管理员账号才能打开」）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_file_share")
public class SysFileShare {
    private Long id;
    private Long fileId;
    /** 随机令牌，不是 id——拿 id 当分享链接等于把整个网盘按序号公开 */
    private String token;
    private Integer requireLogin;
    private LocalDateTime expireTime;
    private Integer downloadCount;
    private Long createBy;
    private LocalDateTime createTime;
    @TableLogic
    private Integer isDeleted;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeToken;

    public boolean usableAt(LocalDateTime now) {
        return expireTime == null || expireTime.isAfter(now);
    }
}
