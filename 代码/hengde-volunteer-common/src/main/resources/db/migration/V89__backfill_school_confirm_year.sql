-- V89 回填「学校年级已确认到的学年」：注册那一刻填的学校年级就算那个学年确认过。
--
-- 注册在当前学年的人上线后不会被提示，更早注册的在这个学年第一次打开时确认一次——这正是「每年确认一次」的意思。
-- 与 V88 分开：ALTER 隐式提交后 UPDATE 再失败，重跑会撞「列已存在」。
-- 学年口径与 Grade.schoolYearOf 一致：9 月 1 日及以后算当年学年，之前算上一年。
UPDATE volunteer
SET school_confirm_year = YEAR(register_time) - IF(MONTH(register_time) < 9, 1, 0)
WHERE register_time IS NOT NULL
  AND school_confirm_year IS NULL;
