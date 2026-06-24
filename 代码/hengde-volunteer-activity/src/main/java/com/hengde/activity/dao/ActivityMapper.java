package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.vo.RecommendActivityVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动 Mapper。
 *
 * @author hengde
 */
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 志愿者端「推荐活动」分页：仅已发布，带出报名人数(enrolled_count) 与有名额标记(has_quota)，
     * 排序＝<b>有名额优先，其次按活动开始时间倒序（最新在前）</b>（需求：优先展示有名额的活动，
     * 都没名额则按最新活动时间）。
     *
     * <p>名额口径：任一时间段 {@code need_count=0}（不限）或 该段活跃报名(0/1)数 &lt; need_count，
     * 即视为该活动「有名额」。报名人数＝活跃报名去重志愿者数。排序在 DB 层做，保证跨分页正确。</p>
     *
     * @param page    分页对象（api 运行期由分页拦截器加 LIMIT；领域测试上下文无拦截器返回全部）
     * @param keyword 标题模糊匹配；null 表示不限
     */
    @Select("""
            SELECT a.id, a.serial_no, a.title, a.cover_image_url, a.location,
                   a.start_time, a.end_time, a.enroll_deadline, a.need_audit, a.status,
                   a.enroll_open_volunteer, a.run_status,
                   (SELECT COUNT(DISTINCT e2.volunteer_id) FROM activity_enrollment e2
                      WHERE e2.activity_id = a.id AND e2.status IN (0, 1) AND e2.is_deleted = 0) AS enrolled_count,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM activity_slot s
                       WHERE s.activity_id = a.id AND s.is_deleted = 0
                         AND (s.need_count = 0
                              OR s.need_count > (SELECT COUNT(*) FROM activity_enrollment e
                                                 WHERE e.slot_id = s.id AND e.status IN (0, 1) AND e.is_deleted = 0))
                   ) THEN 1 ELSE 0 END AS has_quota
            FROM activity a
            WHERE a.status = 1 AND a.is_deleted = 0
              AND (#{keyword} IS NULL OR a.title LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY has_quota DESC, a.start_time DESC, a.id DESC
            """)
    IPage<RecommendActivityVO> selectRecommendPage(IPage<RecommendActivityVO> page, @Param("keyword") String keyword);
}
