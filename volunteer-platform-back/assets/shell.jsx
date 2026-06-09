/* ============================================================
   App 外壳：导航配置 / 侧边栏（权限过滤）/ 顶栏（面包屑·身份切换·账号）/ 水印
   ============================================================ */

/* 导航树：code = 控制可见性的权限点（数组=任一即可见，null=登录即可） */
var NAV_GROUPS = [
  { items: [
    { key: 'overview', label: '概览', icon: 'dashboard', code: null },
  ]},
  { label: '用户管理', items: [
    { key: 'volunteers', label: '志愿者管理', icon: 'user', code: 'user:list' },
  ]},
  { label: '活动管理', items: [
    { key: 'activities', label: '活动列表/发布', icon: 'calendar', code: 'activity:menu' },
    { key: 'activityReview', label: '活动发布审核', icon: 'checkCircle', code: 'activity:publish-audit', badge: 'publishReview' },
    { key: 'enroll', label: '报名管理', icon: 'users', code: 'activity:enroll-view', badge: 'enroll' },
    { key: 'service', label: '服务记录与积分', icon: 'award', code: ['activity:service-confirm', 'activity:points-grant'], badge: 'service' },
    { key: 'attendance', label: '考勤变更审核', icon: 'swap', code: 'activity:attendance-audit', badge: 'attendance' },
    { key: 'backfill', label: '活动补录审核', icon: 'history', code: 'activity:backfill-audit', badge: 'backfill' },
  ]},
  { label: '组织管理', items: [
    { key: 'groups', label: '志愿小组', icon: 'team', code: ['org:group-manage', 'org:group-audit'], badge: 'group' },
    { key: 'squads', label: '归属分队', icon: 'squad', code: ['org:squad-manage', 'org:squad-audit'], badge: 'squad' },
    { key: 'flag', label: '志愿者标记与授权', icon: 'userCheck', code: 'org:manager-flag' },
    { key: 'subaccounts', label: '子账号与权限', icon: 'key', code: 'org:sub-account' },
  ]},
  { label: '信息公示', items: [
    { key: 'banners', label: '轮播图', icon: 'image', code: 'pub:banner' },
    { key: 'announcements', label: '公告', icon: 'bell', code: 'pub:announcement' },
    { key: 'files', label: '文件下载', icon: 'file', code: 'pub:file' },
  ]},
];

var PAGE_TITLES = {
  overview: ['概览', null], volunteers: ['志愿者管理', '用户管理'], activities: ['活动列表/发布', '活动管理'], activityReview: ['活动发布审核', '活动管理'], enroll: ['报名管理', '活动管理'],
  service: ['服务记录与积分', '活动管理'], attendance: ['考勤变更审核', '活动管理'],
  backfill: ['活动补录审核', '活动管理'], groups: ['志愿小组', '组织管理'], squads: ['归属分队', '组织管理'],
  flag: ['志愿者标记与授权', '组织管理'], subaccounts: ['子账号与权限', '组织管理'],
  banners: ['轮播图', '信息公示'], announcements: ['公告', '信息公示'], files: ['文件下载', '信息公示'],
};

/* 菜单徽标计数（待办） */
function badgeCount(kind) {
  if (kind === 'enroll') { var c = 0; Object.keys(HD.ENROLLMENTS).forEach(function (k) { HD.ENROLLMENTS[k].forEach(function (e) { if (e.enrollStatus === 0) c++; }); }); return c; }
  if (kind === 'squad') { var sc = 0; Object.keys(HD.SQUAD_APPLICATIONS).forEach(function (k) { HD.SQUAD_APPLICATIONS[k].forEach(function (a) { if (a.status === 0) sc++; }); }); return sc; }
  if (kind === 'publishReview') return HD.PENDING_ACTIVITIES.filter(function (r) { return r.status === 4; }).length;
  if (kind === 'service') return HD.SERVICE_RECORDS.filter(function (r) { return r.secretaryStatus === 0; }).length;
  if (kind === 'attendance') return HD.ATTENDANCE_CHANGES.filter(function (r) { return r.status === 0; }).length;
  if (kind === 'backfill') return HD.BACKFILLS.filter(function (r) { return r.status === 0; }).length;
  if (kind === 'group') return HD.GROUP_APPLICATIONS.filter(function (r) { return r.status === 0; }).length;
  return 0;
}

/* ---------- 侧边栏 ---------- */
function Sidebar(props) {
  var id = props.identity, collapsed = props.collapsed, active = props.active;
  return React.createElement('div', { className: 'sider' + (collapsed ? ' collapsed' : '') },
    React.createElement('div', { className: 'sider-logo' },
      React.createElement('span', { className: 'logo-mark' }, '恒德'),
      React.createElement('span', { className: 'logo-text' },
        React.createElement('b', null, '恒德志愿者平台'),
        React.createElement('span', null, '运营管理后台'))),
    React.createElement('div', { className: 'menu' }, NAV_GROUPS.map(function (g, gi) {
      var visible = g.items.filter(function (it) { return HD.hasPerm(id, it.code); });
      if (visible.length === 0) return null;
      return React.createElement('div', { key: gi },
        g.label ? React.createElement('div', { className: 'menu-group-label' }, g.label) : null,
        visible.map(function (it) {
          var bc = it.badge ? badgeCount(it.badge) : 0;
          return React.createElement('div', { key: it.key, className: 'menu-item' + (active === it.key ? ' active' : ''),
            onClick: function () { props.onNav(it.key); }, title: collapsed ? it.label : undefined },
            React.createElement('span', { className: 'mi-icon' }, React.createElement(Icon, { name: it.icon, size: 17 })),
            React.createElement('span', { className: 'mi-text' }, it.label),
            bc > 0 ? React.createElement('span', { className: 'mi-badge' }, bc) : null);
        }));
    })));
}

/* ---------- 顶栏 ---------- */
var IDENTITY_OPTIONS = [
  { value: 'super', label: '超级管理员', badge: 'super', sub: '陈国栋 · 通配 *' },
  { value: 'org', label: '组织部 子账号', badge: 'dept', sub: '林海燕 · 报名/建组/分队' },
  { value: 'secretary', label: '秘书部 子账号', badge: 'dept', sub: '吴敏 · 时长/积分确认' },
  { value: 'publicity', label: '宣传部 子账号', badge: 'dept', sub: '黄梓萱 · 轮播/公告/文件' },
];

function IdentitySwitcher(props) {
  var ref = useRef(); var [open, setOpen] = useState(false);
  useClickOutside(ref, function () { setOpen(false); }, open);
  var id = props.identity;
  var cur = IDENTITY_OPTIONS.filter(function (o) { return o.value === id.key; })[0] || IDENTITY_OPTIONS[0];
  return React.createElement('div', { className: 'dropdown', ref: ref },
    React.createElement('div', { className: 'id-switch', onClick: function () { setOpen(!open); }, title: '切换登录身份以预览权限差异' },
      React.createElement(Icon, { name: 'swap', size: 15, style: { color: 'var(--text-3)' } }),
      React.createElement('span', { className: 'id-role' }, cur.label),
      React.createElement('span', { className: 'id-badge ' + cur.badge }, cur.badge === 'super' ? '超管' : '部门'),
      React.createElement(Icon, { name: 'chevronDown', size: 13, style: { color: 'var(--text-3)' } })),
    open ? React.createElement('div', { className: 'dropdown-menu', style: { minWidth: 248, padding: 6 } },
      React.createElement('div', { style: { padding: '6px 10px 8px', fontSize: 12, color: 'var(--text-3)', borderBottom: '1px solid var(--split)', marginBottom: 4 } },
        '切换身份 · 预览不同账号的菜单与按钮权限'),
      IDENTITY_OPTIONS.map(function (o) {
        return React.createElement('div', { key: o.value, className: 'dd-item', style: { alignItems: 'flex-start', padding: '8px 10px' },
          onClick: function () { setOpen(false); props.onChange(o.value); } },
          React.createElement('span', { className: 'id-badge ' + o.badge, style: { marginTop: 2 } }, o.badge === 'super' ? '超管' : '部门'),
          React.createElement('div', { style: { display: 'flex', flexDirection: 'column', lineHeight: 1.35 } },
            React.createElement('b', { style: { fontWeight: 500, color: o.value === id.key ? 'var(--primary)' : 'var(--text)' } }, o.label),
            React.createElement('span', { style: { fontSize: 12, color: 'var(--text-3)' } }, o.sub)),
          o.value === id.key ? React.createElement(Icon, { name: 'check', size: 15, style: { marginLeft: 'auto', color: 'var(--primary)' } }) : null);
      })) : null);
}

function Topbar(props) {
  var id = props.identity;
  var tt = PAGE_TITLES[props.active] || ['', null];
  var ref = useRef(); var [acctOpen, setAcctOpen] = useState(false);
  useClickOutside(ref, function () { setAcctOpen(false); }, acctOpen);
  return React.createElement('div', { className: 'topbar' },
    React.createElement('button', { className: 'collapse-btn', onClick: props.onToggleSider, title: '折叠/展开侧边栏' },
      React.createElement(Icon, { name: props.collapsed ? 'menuUnfold' : 'menuFold', size: 18 })),
    React.createElement('div', { className: 'breadcrumb' },
      React.createElement('span', { className: 'crumb' }, '恒德后台'),
      tt[1] ? React.createElement(React.Fragment, null,
        React.createElement('span', { className: 'sep' }, '/'),
        React.createElement('span', { className: 'crumb' }, tt[1])) : null,
      React.createElement('span', { className: 'sep' }, '/'),
      React.createElement('span', { className: 'crumb cur' }, tt[0])),
    React.createElement('div', { className: 'topbar-right' },
      props.realMode
        ? React.createElement('span', { style: { fontSize: 12, color: 'var(--text-3)', padding: '0 6px' } }, '真实账号模式')
        : React.createElement(IdentitySwitcher, { identity: id, onChange: props.onIdentity }),
      React.createElement('div', { style: { width: 1, height: 20, background: 'var(--split)', margin: '0 4px' } }),
      React.createElement('div', { className: 'dropdown', ref: ref },
        React.createElement('div', { className: 'acct', onClick: function () { setAcctOpen(!acctOpen); } },
          React.createElement(Avatar, { name: id.name, size: 'sm' }),
          React.createElement('div', { className: 'acct-meta' },
            React.createElement('b', null, id.name),
            React.createElement('span', null, id.dept)),
          React.createElement(Icon, { name: 'chevronDown', size: 13, style: { color: 'var(--text-3)' } })),
        acctOpen ? React.createElement('div', { className: 'dropdown-menu', style: { minWidth: 170 } },
          React.createElement('div', { style: { padding: '8px 12px', borderBottom: '1px solid var(--split)', marginBottom: 4 } },
            React.createElement('div', { style: { fontWeight: 500 } }, id.name + (id.isSuperAdmin ? '（超管）' : '')),
            React.createElement('div', { style: { fontSize: 12, color: 'var(--text-3)' } }, id.account + ' · ' + id.dept)),
          React.createElement('div', { className: 'dd-item', onClick: function () { setAcctOpen(false); props.onChangePwd(); } },
            React.createElement(Icon, { name: 'lock', size: 15 }), '修改密码'),
          React.createElement('div', { className: 'dd-divider' }),
          React.createElement('div', { className: 'dd-item danger', onClick: function () { setAcctOpen(false); props.onLogout && props.onLogout(); } },
            React.createElement(Icon, { name: 'logout', size: 15 }), '退出登录')) : null)));
}

/* ---------- 防泄密水印（平铺，淡） ---------- */
function Watermark(props) {
  var id = props.identity;
  var text = id.name + ' · ' + id.phoneTail + ' · ' + id.dept;
  var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="150">' +
    '<text x="0" y="100" transform="rotate(-22 120 75)" fill="rgba(0,0,0,0.045)" font-size="14" font-family="sans-serif">' +
    text + '</text></svg>';
  var url = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
  return React.createElement('div', { className: 'watermark', style: { backgroundImage: 'url(' + url + ')' } });
}

Object.assign(window, { NAV_GROUPS: NAV_GROUPS, Sidebar: Sidebar, Topbar: Topbar, Watermark: Watermark, badgeCount: badgeCount, PAGE_TITLES: PAGE_TITLES });
