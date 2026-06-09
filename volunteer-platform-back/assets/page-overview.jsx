/* ============================================================
   概览页：数据看板头部统计（GET /a/data/dashboard）+ 待办计数卡片
   - 头部 6 项为非敏感聚合（登录即可），一次 /a/data/dashboard 取回
   - 待办计数走各自「权限受控」的列表端点 size=1 取 total；只对当前账号有权的卡片发请求（避免 403）
   ============================================================ */
function OverviewPage(props) {
  var id = props.identity, go = props.onNav;
  var [stats, setStats] = useState(null);   // 头部看板
  var [counts, setCounts] = useState({});   // key -> 待办数

  // 待办卡片：code=菜单/卡片可见性；countPerm=该计数端点的真实权限（据此决定是否发请求，防 403）
  var cards = [
    { key: 'publishReview', label: '待审活动发布', icon: 'checkCircle', color: '#1677ff', target: 'activityReview',
      code: 'activity:publish-audit', countPerm: 'activity:publish-audit',
      path: '/a/activity/activities/pending-reviews', query: { status: 4, page: 1, size: 1 },
      source: 'GET /a/activity/activities/pending-reviews?status=4' },
    { key: 'group', label: '待审建组申请', icon: 'team', color: '#fa8c16', target: 'groups',
      code: ['org:group-manage', 'org:group-audit'], countPerm: 'org:group-audit',
      path: '/a/organization/groups/applications', query: { page: 1, size: 1 },
      source: 'GET /a/organization/groups/applications' },
    { key: 'squad', label: '待审分队加入', icon: 'squad', color: '#13a8a8', target: 'squads',
      code: ['org:squad-manage', 'org:squad-audit'], countPerm: 'org:squad-audit',
      path: '/a/organization/squads/applications', query: { status: 0, page: 1, size: 1 },
      source: 'GET /a/organization/squads/applications?status=0' },
    { key: 'enroll', label: '待审报名', icon: 'users', color: '#722ed1', target: 'enroll',
      code: 'activity:enroll-view', countPerm: 'activity:enroll-view',
      path: '/a/activity/enrollments', query: { status: 0, page: 1, size: 1 },
      source: 'GET /a/activity/enrollments?status=0（全局）' },
    { key: 'attendance', label: '待审考勤变更', icon: 'swap', color: '#2f54eb', target: 'attendance',
      code: 'activity:attendance-audit', countPerm: 'activity:attendance-audit',
      path: '/a/activity/attendance-changes', query: { status: 0, page: 1, size: 1 },
      source: 'GET /a/activity/attendance-changes?status=0' },
    { key: 'backfill', label: '待补录审核', icon: 'history', color: '#eb2f96', target: 'backfill',
      code: 'activity:backfill-audit', countPerm: 'activity:backfill-audit',
      path: '/a/activity/backfills', query: { status: 0, page: 1, size: 1 },
      source: 'GET /a/activity/backfills?status=0' },
    { key: 'service', label: '待秘书部确认时长', icon: 'award', color: '#52c41a', target: 'service',
      code: ['activity:service-confirm', 'activity:points-grant'], countPerm: 'activity:service-confirm',
      path: '/a/activity/service-records/pending', query: { page: 1, size: 1 },
      source: 'GET /a/activity/service-records/pending' },
  ];

  useEffect(function () {
    API.get('/a/data/dashboard').then(function (d) { setStats(d || {}); }).catch(function () { setStats({}); });
    cards.forEach(function (c) {
      if (!HD.hasPerm(id, c.countPerm)) return;   // 仅对有权账号发计数请求，避免 403
      API.get(c.path, c.query).then(function (r) {
        var total = Number((r && r.total) || 0);
        setCounts(function (p) { var n = Object.assign({}, p); n[c.key] = total; return n; });
      }).catch(function () { /* 单卡失败不影响其它 */ });
    });
  }, []);

  function statVal(field) {
    if (!stats || stats[field] == null) return null;
    var v = Number(stats[field]);
    return isNaN(v) ? null : v;
  }
  var headline = [
    ['注册志愿者', 'registeredVolunteers', '人', '#1677ff'],
    ['活动场次', 'activityCount', '场', '#722ed1'],
    ['总服务时长', 'totalServiceHours', 'h', '#13a8a8'],
    ['参与人次', 'participationCount', '人次', '#52c41a'],
    ['管理团队', 'managerCount', '人', '#fa8c16'],
    ['分队', 'squadCount', '个', '#eb2f96'],
  ];

  return React.createElement('div', { className: 'page' },
    React.createElement('div', { className: 'page-head' },
      React.createElement('div', { className: 'ph-text' },
        React.createElement('h1', null, '你好，' + id.name + '　',
          React.createElement('span', { style: { fontSize: 13, fontWeight: 400, color: 'var(--text-3)' } },
            id.isSuperAdmin ? '超级管理员 · 可见全部模块' : (id.dept || '') + ' · 按授权权限点显示菜单')),
        React.createElement('p', null, '平台数据看板（GET /a/data/dashboard）+ 各模块待办计数。待办数据来自各自权限受控的列表接口，仅对你有权的项发起请求。'))),

    // 头部数据看板
    React.createElement('div', { style: { fontSize: 13, fontWeight: 600, color: 'var(--text-2)', margin: '4px 0 12px' } }, '数据概览'),
    React.createElement('div', { style: { display: 'flex', gap: 12, marginBottom: 22, flexWrap: 'wrap' } },
      headline.map(function (s, i) {
        var v = statVal(s[1]);
        return React.createElement('div', { key: i, className: 'card', style: { flex: '1 1 150px', padding: '14px 16px', display: 'flex', alignItems: 'center', gap: 12 } },
          React.createElement('span', { style: { width: 8, height: 8, borderRadius: '50%', background: s[3] } }),
          React.createElement('div', null,
            React.createElement('div', { style: { fontSize: 22, fontWeight: 600, lineHeight: 1, fontVariantNumeric: 'tabular-nums' } },
              v == null ? '—' : v, React.createElement('span', { style: { fontSize: 12, fontWeight: 400, color: 'var(--text-3)', marginLeft: 4 } }, s[2])),
            React.createElement('div', { className: 'cell-sub', style: { marginTop: 3 } }, s[0])));
      })),

    // 待办入口
    React.createElement('div', { style: { fontSize: 13, fontWeight: 600, color: 'var(--text-2)', margin: '4px 0 12px' } }, '待办入口'),
    React.createElement('div', { className: 'stat-grid' }, cards.map(function (c) {
      var canAccess = HD.hasPerm(id, c.code);
      var hasCount = Object.prototype.hasOwnProperty.call(counts, c.key);
      var val = counts[c.key];
      return React.createElement('div', { key: c.key, className: 'stat-card' + (canAccess ? '' : ' disabled'),
        onClick: canAccess ? function () { go(c.target); } : undefined },
        React.createElement('div', { className: 'sc-top' },
          React.createElement('span', { className: 'sc-label' }, c.label),
          React.createElement('span', { className: 'sc-icon', style: { background: c.color + '1f', color: c.color } },
            React.createElement(Icon, { name: c.icon, size: 19 }))),
        React.createElement('div', { className: 'sc-val' },
          !canAccess ? React.createElement('span', { style: { fontSize: 15, color: 'var(--text-4)', fontWeight: 400 } }, '无权限')
            : !hasCount ? React.createElement('span', { style: { fontSize: 18, color: 'var(--text-4)', fontWeight: 400 } }, '—')
            : React.createElement(React.Fragment, null, val, React.createElement('span', { className: 'unit' }, '项')),
          (canAccess && hasCount && val > 0) ? React.createElement('span', { className: 'badge-dot warning', style: { marginLeft: 10, fontSize: 12, fontWeight: 400, color: 'var(--text-3)' } }, '待处理') : null),
        React.createElement('div', { className: 'sc-source' }, c.source),
        canAccess ? React.createElement('span', { className: 'go' }, React.createElement(Icon, { name: 'arrowRightShort', size: 16 })) : null);
    })),
    React.createElement('div', { style: { fontSize: 12, color: 'var(--text-4)', marginTop: 18 } },
      '注：头部为平台聚合数字（登录即可）；待办卡片「无权限」表示当前账号未获该审核/查看权限点，计数仅对有权项请求。'));
}
window.OverviewPage = OverviewPage;
