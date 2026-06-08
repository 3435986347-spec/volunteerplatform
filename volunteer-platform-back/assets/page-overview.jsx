/* ============================================================
   概览页：待办计数卡片（点击跳转到带筛选的列表）+ 数据来源标注
   后端暂无统计接口，数字由各列表 status=待审 的 total 拼出。
   ============================================================ */
function OverviewPage(props) {
  var id = props.identity, go = props.onNav;
  var pendEnroll = 0; Object.keys(HD.ENROLLMENTS).forEach(function (k) { HD.ENROLLMENTS[k].forEach(function (e) { if (e.enrollStatus === 0) pendEnroll++; }); });
  var pendSquad = 0; Object.keys(HD.SQUAD_APPLICATIONS).forEach(function (k) { HD.SQUAD_APPLICATIONS[k].forEach(function (a) { if (a.status === 0) pendSquad++; }); });
  var cards = [
    { key: 'publishReview', label: '待审活动发布', icon: 'checkCircle', color: '#1677ff', target: 'activityReview', code: 'activity:publish-audit',
      value: HD.PENDING_ACTIVITIES.filter(function (r) { return r.status === 4; }).length,
      source: 'GET /a/activity/activities/pending-reviews（status 默认 4 待审核）✓' },
    { key: 'group', label: '待审建组申请', icon: 'team', color: '#fa8c16', target: 'groups', code: ['org:group-manage', 'org:group-audit'],
      value: HD.GROUP_APPLICATIONS.filter(function (r) { return r.status === 0; }).length,
      source: 'GET /a/organization/groups/applications · 全局接口可直接计数 ✓' },
    { key: 'squad', label: '待审分队加入', icon: 'squad', color: '#13a8a8', target: 'squads', code: ['org:squad-manage', 'org:squad-audit'],
      value: pendSquad,
      source: 'GET /a/organization/squads/applications?status=0 · 全局接口（每行带 squadName）✓ 文档已提供' },
    { key: 'enroll', label: '待审报名', icon: 'users', color: '#722ed1', target: 'enroll', code: 'activity:enroll-view',
      value: pendEnroll,
      source: '建议补充全局接口 GET /a/activity/enrollments?status=0（文档暂按活动 /activities/{id}/enrollments）' },
    { key: 'attendance', label: '待审考勤变更', icon: 'swap', color: '#2f54eb', target: 'attendance', code: 'activity:attendance-audit',
      value: HD.ATTENDANCE_CHANGES.filter(function (r) { return r.status === 0; }).length,
      source: 'GET /a/activity/attendance-changes?status=0 ✓' },
    { key: 'backfill', label: '待补录审核', icon: 'history', color: '#eb2f96', target: 'backfill', code: 'activity:backfill-audit',
      value: HD.BACKFILLS.filter(function (r) { return r.status === 0; }).length,
      source: 'GET /a/activity/backfills?status=0 ✓' },
    { key: 'service', label: '待秘书部确认时长', icon: 'award', color: '#52c41a', target: 'service', code: ['activity:service-confirm', 'activity:points-grant'],
      value: HD.SERVICE_RECORDS.filter(function (r) { return r.secretaryStatus === 0; }).length,
      source: 'GET /a/activity/service-records/pending ✓' },
  ];
  return React.createElement('div', { className: 'page' },
    React.createElement('div', { className: 'page-head' },
      React.createElement('div', { className: 'ph-text' },
        React.createElement('h1', null, '你好，' + id.name + '　',
          React.createElement('span', { style: { fontSize: 13, fontWeight: 400, color: 'var(--text-3)' } },
            id.isSuperAdmin ? '超级管理员 · 可见全部模块' : id.dept + ' · 按授权权限点显示菜单')),
        React.createElement('p', null, '聚合各模块的待办计数，点击卡片直达对应列表（已带状态筛选）。后端暂无专用统计接口，以下数字由各列表「待审」的 total 拼出。'))),
    React.createElement('div', { style: { fontSize: 13, fontWeight: 600, color: 'var(--text-2)', margin: '4px 0 12px' } }, '待办入口'),
    React.createElement('div', { className: 'stat-grid' }, cards.map(function (c) {
      var canAccess = HD.hasPerm(id, c.code);
      var disabled = c.placeholder || !canAccess;
      return React.createElement('div', { key: c.key, className: 'stat-card' + (disabled ? ' disabled' : ''),
        onClick: disabled ? undefined : function () { go(c.target); } },
        React.createElement('div', { className: 'sc-top' },
          React.createElement('span', { className: 'sc-label' }, c.label),
          React.createElement('span', { className: 'sc-icon', style: { background: c.color + '1f', color: c.color } },
            React.createElement(Icon, { name: c.icon, size: 19 }))),
        React.createElement('div', { className: 'sc-val' },
          c.placeholder ? React.createElement('span', { style: { fontSize: 18, color: 'var(--text-4)', fontWeight: 400 } }, '待接口')
            : !canAccess ? React.createElement('span', { style: { fontSize: 15, color: 'var(--text-4)', fontWeight: 400 } }, '无权限')
            : React.createElement(React.Fragment, null, c.value, React.createElement('span', { className: 'unit' }, '项')),
          (!disabled && c.value > 0) ? React.createElement('span', { className: 'badge-dot warning', style: { marginLeft: 10, fontSize: 12, fontWeight: 400, color: 'var(--text-3)' } }, '待处理') : null),
        React.createElement('div', { className: 'sc-source' }, c.source),
        !disabled ? React.createElement('span', { className: 'go' }, React.createElement(Icon, { name: 'arrowRightShort', size: 16 })) : null);
    })),
    React.createElement('div', { style: { fontSize: 12, color: 'var(--text-4)', marginTop: 18 } },
      '注：后台数据看板 GET /a/data/dashboard 已在 url 文档列出（注册人数/场次/时长/参与人次/管理团队/分队数）；本概览暂仍以「待办入口 + 简单计数」为主，未绘制营收/趋势大图。'));
}
window.OverviewPage = OverviewPage;
