package com.hengde.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 文件夹授权（V83，Row 71「可以设权限」）：授给某个后台账号，或授给一整个部门。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("sys_folder_grant")
public class SysFolderGrant {
    private Long id;
    private Long folderId;
    private Integer granteeType;
    private Long adminId;
    private String department;
    private Integer canWrite;
    private LocalDateTime createTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String grantKey;
}
