package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 圆梦微心愿（V51）。
 *
 * <p>{@link #childName} / {@link #childSchool} 是<b>密文</b>（受助人是未成年人），
 * 读出来之后由服务层按「是不是认领人」决定解密后给全文、还是打 * 给一部分。
 * 生成列 {@code active_no_key} 不映射（数据库计算）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_wish")
public class DonateWish extends BaseEntity {

    private String wishNo;
    private String title;
    private String content;
    private String story;
    private String imageUrl;
    /** 密文 */
    private String childName;
    private Integer childGender;
    private Integer childAge;
    /** 密文 */
    private String childSchool;
    private String childGrade;
    private Long reportOrgId;
    private String reportOrgName;
    private String remark;
    /** 见 {@code WishFlow.WISH_*} */
    private Integer status;
    private LocalDateTime realizeTime;
    /** 换行分隔的图片 URL */
    private String feedbackImages;
    private Long createBy;
}
