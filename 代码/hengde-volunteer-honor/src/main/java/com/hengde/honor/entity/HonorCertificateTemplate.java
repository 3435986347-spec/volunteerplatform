package com.hengde.honor.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 证书电子样本（V31）。
 *
 * <p>需求原文 xlsx Row 36 F 列第 ③ 项：「<b>设置某个活动的电子样本</b>」——模板绑<b>活动</b>，
 * 不是全局一套；本设计另留一个 {@code global} 兜底作用域。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("honor_certificate_template")
public class HonorCertificateTemplate extends BaseEntity {

    /** 全局默认作用域的固定键 */
    public static final String SCOPE_GLOBAL = "global";

    /** 按活动的作用域键前缀 */
    public static final String SCOPE_ACTIVITY_PREFIX = "activity:";

    /**
     * 捐赠证书的作用域键（V3 结对批）。
     *
     * <p>协会现有的电子样本只有活动证书那一张（《协会待确认清单-v3》⑨ 未答）。
     * 出捐赠证书时<b>先找这个作用域、找不到退回 {@link #SCOPE_GLOBAL}</b>：
     * 协会日后给了专用样本，配一条这个键即可生效，代码不用动。</p>
     */
    public static final String SCOPE_DONATION = "donate-pair";

    /**
     * 作用域键：{@code activity:{活动id}} 或 {@code global}。
     *
     * <p><b>为什么不用可空的 activity_id</b>：MySQL 唯一索引视多个 NULL 互不相同，
     * 用 {@code activity_id} 表达「全局默认」会放进任意多条，取模板时不确定命中哪一条。</p>
     */
    private String scopeKey;

    /** 样本名称，后台列表展示 */
    private String name;

    /** 样本底图/模板文件的私有对象 key */
    private String fileKey;

    /** 字段与盖章坐标配置（JSON 文本） */
    private String layout;

    /** 启用 0否/1是 */
    private Integer enabled;

    /** 创建人 admin_user.id */
    private Long createBy;

    /**
     * 「未删行的作用域键」，<b>由数据库生成列计算</b>，应用不可写。
     *
     * <p>{@code insertStrategy/updateStrategy = NEVER} 让 MP 永不把它写进 SQL——
     * 否则 INSERT 会因为写生成列而报错。</p>
     */
    @TableField(value = "active_scope_key",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private String activeScopeKey;

    /** 按活动 id 组装作用域键。 */
    public static String activityScope(Long activityId) {
        return SCOPE_ACTIVITY_PREFIX + activityId;
    }
}
