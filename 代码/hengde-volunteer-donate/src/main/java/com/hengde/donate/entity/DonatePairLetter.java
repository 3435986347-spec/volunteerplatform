package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 受助方来信（V52，Row 10 图文展示板块）。受助方不直接使用系统，信由协会代为录入。
 *
 * <p>{@link #pairRecordId} 为空表示<b>项目公开信</b>（所有人可见）；非空表示写给那一位结对人的信，
 * 只有他看得到——判定在服务端。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_pair_letter")
public class DonatePairLetter extends BaseEntity {

    private Long projectId;
    private Long pairRecordId;
    private String title;
    private String content;
    /** 换行分隔的图片 URL */
    private String imageUrls;
    private LocalDateTime writeTime;
    private Long createBy;
}
