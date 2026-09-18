-- 存量志愿者的 grade_upgrade_year 回填（V4 个人中心补全批，接 V67）。
--
-- 按「年级最后一次被确认的时间」推学年：没有更准的时间，只能取实名注册时间（没有就取建号时间）。
--   9 月及以后注册的算当年学年，之前的算上一学年——这样上线后第一次跑升级任务，只有「上一个 9 月 1 日之前」就填了年级的人会升一级。
-- ⚠️ 已知的不准：注册之后在「我的资料」里改过年级的人，按注册时间推会早一年，上线当年可能被多升一级；
--   代价是一格年级，本人改回来即可（改年级会把学年置为当前）。没有年级的行不回填，任务也不会碰它们。

UPDATE volunteer
SET grade_upgrade_year = CASE
        WHEN MONTH(COALESCE(register_time, create_time)) >= 9 THEN YEAR(COALESCE(register_time, create_time))
        ELSE YEAR(COALESCE(register_time, create_time)) - 1
    END
WHERE grade IS NOT NULL AND grade_upgrade_year IS NULL;
