package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微心愿详情的查看记录（V100，协会 2026-10-16 答复五 丁-7）。只追加 / 累加，没有逻辑删除。
 *
 * @author hengde
 */
@Data
@TableName("donate_wish_viewer")
public class DonateWishViewer {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long wishId;
    private Long volunteerId;
    /** 查看时快照的手机号（密文） */
    private String phone;
    private Integer viewCount;
    private LocalDateTime firstViewTime;
    private LocalDateTime lastViewTime;
}
