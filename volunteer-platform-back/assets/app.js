/* ============================================================
   主应用：登录页 / 路由 / 身份 / Tweaks / 挂载
   ============================================================ */
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "primaryColor": "#1677ff",
  "identity": "super",
  "density": "regular",
  "watermark": true,
  "siderCollapsed": false,
  "zebra": false
}/*EDITMODE-END*/;

/* 主色 → 派生色（hover/active/bg），写入 CSS 变量 */
function hexToRgb(h) { h = h.replace('#', ''); if (h.length === 3) h = h.split('').map(function (c) { return c + c; }).join(''); return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]; }
function mix(rgb, target, t) { return rgb.map(function (c, i) { return Math.round(c + (target[i] - c) * t); }); }
function toHex(rgb) { return '#' + rgb.map(function (c) { return ('0' + c.toString(16)).slice(-2); }).join(''); }
function applyPrimary(hex) {
  var rgb = hexToRgb(hex);
  var root = document.documentElement.style;
  root.setProperty('--primary', hex);
  root.setProperty('--primary-hover', toHex(mix(rgb, [255, 255, 255], 0.2)));
  root.setProperty('--primary-active', toHex(mix(rgb, [0, 0, 0], 0.15)));
  root.setProperty('--primary-bg', toHex(mix(rgb, [255, 255, 255], 0.9)));
  root.setProperty('--primary-bg-hover', toHex(mix(rgb, [255, 255, 255], 0.82)));
  root.setProperty('--primary-border', toHex(mix(rgb, [255, 255, 255], 0.55)));
  root.setProperty('--processing', hex);
}

var PAGE_COMPONENTS = {
  overview: OverviewPage, activities: ActivitiesPage, activityReview: ActivityReviewPage, enroll: EnrollPage,
  volunteers: VolunteersPage,
  service: ServicePage, attendance: AttendanceChangePage, backfill: BackfillPage,
  groups: GroupsPage, squads: SquadsPage, flag: FlagPage, managerApply: ManagerApplicationsPage, subaccounts: SubAccountsPage,
  banners: BannersPage, announcements: AnnouncementsPage, files: FilesPage,
};

/* GET /a/auth/me 出参 → 现有 identity 形状（喂 hasPerm / 顶栏 / 水印） */
function meToIdentity(me) {
  return {
    key: 'me', adminId: me.adminId, name: me.realName || me.username, account: me.username,
    dept: me.department || '', phone: '', phoneTail: '',
    isSuperAdmin: !!me.superAdmin,
    permissionCodes: me.permissionCodes || [],
    roleLabel: me.superAdmin ? '超级管理员' : ((me.department || '') + ' · 子账号'),
  };
}

/* ---------- 启动占位（有 token 时先恢复会话，避免闪登录页） ---------- */
function BootSplash() {
  return React.createElement('div', { style: { height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f5f5f5', color: 'var(--text-3)', fontSize: 14 } }, '加载中…');
}

/* ---------- 登录页 ---------- */
function LoginPage(props) {
  var [mode, setMode] = useState('login'); // login | forgot
  var [account, setAccount] = useState('admin');
  var [pwd, setPwd] = useState('');
  var [busy, setBusy] = useState(false);
  function submit() {
    if (busy) return;
    setBusy(true);
    Promise.resolve(props.onLogin(account, pwd)).catch(function () {}).then(function () { setBusy(false); });
  }
  return React.createElement('div', { style: { height: '100%', display: 'flex', background: '#f5f5f5' } },
    React.createElement('div', { style: { flex: 1, position: 'relative', overflow: 'hidden', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 8% ', color: '#fff',
      background: 'linear-gradient(135deg,#0958d9,#1677ff 55%,#4096ff)' } },
      React.createElement('div', { style: { position: 'absolute', right: -60, bottom: -80, fontSize: 360, fontWeight: 800, color: 'rgba(255,255,255,.07)', lineHeight: 1, userSelect: 'none' } }, '恒'),
      React.createElement('div', { style: { position: 'relative' } },
        React.createElement('div', { style: { fontSize: 13, letterSpacing: 4, opacity: .8, marginBottom: 14 } }, '雷州市恒德爱心公益协会'),
        React.createElement('h1', { style: { fontSize: 38, fontWeight: 700, margin: '0 0 16px', lineHeight: 1.25 } }, '志愿者运营', React.createElement('br', null), '管理后台'),
        React.createElement('p', { style: { fontSize: 15, opacity: .85, maxWidth: 380, lineHeight: 1.7, margin: '0 0 32px' } }, '活动发布 · 报名审核 · 组织管理 · 服务积分 · 信息公示。简洁迅速、权限驱动的协会内部工具。'),
        React.createElement('div', { style: { display: 'flex', flexDirection: 'column', gap: 12 } }, ['活动 / 报名 / 服务积分全链路', '小组·分队·子账号权限分配', '按账号权限点显示菜单与按钮'].map(function (x, i) {
          return React.createElement('div', { key: i, style: { display: 'flex', alignItems: 'center', gap: 10, fontSize: 14, opacity: .92 } },
            React.createElement('span', { style: { width: 20, height: 20, borderRadius: '50%', background: 'rgba(255,255,255,.18)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 20px' } }, React.createElement(Icon, { name: 'check', size: 13 })), x);
        })))),
    React.createElement('div', { style: { flex: '0 0 460px', maxWidth: '100%', background: '#fff', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 56px' } },
      React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 40 } },
        React.createElement('span', { className: 'logo-mark', style: { width: 44, height: 44, fontSize: 18 } }, '恒德'),
        React.createElement('div', null, React.createElement('div', { style: { fontSize: 18, fontWeight: 700 } }, '恒德志愿者平台'),
          React.createElement('div', { style: { fontSize: 13, color: 'var(--text-3)' } }, '雷州市恒德爱心公益协会 · 运营管理后台'))),
      React.createElement('h2', { style: { margin: '0 0 24px', fontSize: 22, fontWeight: 600 } }, mode === 'login' ? '账号登录' : '找回密码'),
      mode === 'login' ? React.createElement(React.Fragment, null,
        React.createElement(Field, { label: '账号' }, React.createElement('div', { className: 'input-affix' }, React.createElement('span', { className: 'prefix' }, React.createElement(Icon, { name: 'user', size: 15 })), React.createElement('input', { className: 'input', style: { paddingLeft: 32, height: 40 }, value: account, onChange: function (e) { setAccount(e.target.value); }, placeholder: '管理员账号' }))),
        React.createElement(Field, { label: '密码' }, React.createElement('div', { className: 'input-affix' }, React.createElement('span', { className: 'prefix' }, React.createElement(Icon, { name: 'lock', size: 15 })), React.createElement('input', { className: 'input', type: 'password', style: { paddingLeft: 32, height: 40 }, value: pwd, onChange: function (e) { setPwd(e.target.value); }, onKeyDown: function (e) { if (e.key === 'Enter') submit(); }, placeholder: '登录密码' }))),
        React.createElement('div', { style: { textAlign: 'right', marginBottom: 20 } }, React.createElement('span', { className: 'btn-link', onClick: function () { setMode('forgot'); } }, '忘记密码？')),
        React.createElement(Btn, { type: 'primary', block: true, size: 'lg', onClick: submit }, busy ? '登录中…' : '登 录'),
        React.createElement('div', { style: { marginTop: 16, fontSize: 12, color: 'var(--text-4)', lineHeight: 1.6 } }, 'POST /a/auth/login（公开）→ 拿 token，之后每请求头带 Authorization；再调 GET /a/auth/me 取权限码渲染菜单/按钮。管理端与志愿者端登录态隔离。')) :
        React.createElement(React.Fragment, null,
          React.createElement(Field, { label: '手机号' }, React.createElement(Input, { style: { height: 40 }, placeholder: '注册手机号' })),
          React.createElement(Field, { label: '短信验证码' }, React.createElement('div', { style: { display: 'flex', gap: 8 } }, React.createElement(Input, { style: { height: 40 }, placeholder: '6 位验证码' }), React.createElement(Btn, { onClick: function () { window.message.success('验证码已发送（demo）'); } }, '获取验证码'))),
          React.createElement(Field, { label: '新密码' }, React.createElement(Input, { type: 'password', style: { height: 40 }, placeholder: '设置新密码' })),
          React.createElement(Btn, { type: 'primary', block: true, size: 'lg', onClick: function () { window.message.success('密码已重置'); setMode('login'); } }, '重置密码'),
          React.createElement('div', { style: { textAlign: 'center', marginTop: 16 } }, React.createElement('span', { className: 'btn-link', onClick: function () { setMode('login'); } }, '返回登录')))));
}

/* ---------- 修改密码弹窗 ---------- */
function ChangePwdModal(props) {
  return React.createElement(Modal, { open: true, title: '修改密码', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('密码已修改'); props.onClose(); } }, '确认修改')) },
    React.createElement(Field, { label: '原密码', required: true }, React.createElement(Input, { type: 'password', placeholder: '请输入原密码' })),
    React.createElement(Field, { label: '新密码', required: true }, React.createElement(Input, { type: 'password', placeholder: '8-20 位，含字母与数字' })),
    React.createElement(Field, { label: '确认新密码', required: true, style: { marginBottom: 0 } }, React.createElement(Input, { type: 'password', placeholder: '再次输入新密码' })));
}

/* ---------- App ---------- */
function App() {
  var [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  var [logged, setLogged] = useState(false);
  var [page, setPage] = useState('overview');
  var [pwdOpen, setPwdOpen] = useState(false);
  var [me, setMe] = useState(null);
  var [booting, setBooting] = useState(!!API.getToken()); // 有 token 才进恢复态，否则直接登录页
  // 真实登录后 me 优先；未登录/纯 mock 预览时回退到 Tweaks 身份
  var identity = me || PREVIEW_IDENTITIES[t.identity] || PREVIEW_IDENTITIES.super;

  useEffect(function () { applyPrimary(t.primaryColor); }, [t.primaryColor]);

  // 当前页/身份变化时复核：换更低权限账号（真实登录/退出/切身份）后若当前页无权限，回概览。
  // 依赖 page + 真实权限集（isSuperAdmin + permissionCodes），而非 t.identity——后者在真实登录模式下不变。
  useEffect(function () {
    var navItem = null;
    NAV_GROUPS.forEach(function (g) { g.items.forEach(function (it) { if (it.key === page) navItem = it; }); });
    if (navItem && !hasPerm(identity, navItem.code)) setPage('overview');
  }, [page, identity.isSuperAdmin, identity.permissionCodes.join('|')]);

  // 真实登录：拿 token → /a/auth/me → 用真实权限码驱动菜单/按钮
  function doLogin(account, pwd) {
    return API.login(account, pwd).then(function () { return API.me(); }).then(function (meVO) {
      setMe(meToIdentity(meVO)); setLogged(true); window.message.success('登录成功');
    });
  }
  // 主动退出：调后端 logout（best-effort）→ 清 token → 回登录页
  function doLogout() {
    API.post('/a/auth/logout').catch(function () {}).then(function () {
      API.setToken(''); setMe(null); setLogged(false); window.message.success('已退出登录');
    });
  }
  // Sa-Token 失效（api.js 抛 hd:unauthorized）→ 回登录页
  useEffect(function () {
    function onUnauth() { setMe(null); setLogged(false); }
    window.addEventListener('hd:unauthorized', onUnauth);
    return function () { window.removeEventListener('hd:unauthorized', onUnauth); };
  }, []);

  // 启动恢复：localStorage 有 token 就先 /a/auth/me 恢复会话，失败则清 token 回登录页
  useEffect(function () {
    if (!API.getToken()) { setBooting(false); return; }
    API.me().then(function (meVO) {
      setMe(meToIdentity(meVO)); setLogged(true);
    }).catch(function () {
      API.setToken('');
    }).then(function () { setBooting(false); });
  }, []);

  // 中央权限守卫：不完全信任入口侧过滤，任何 nav（含概览卡片 go）都按 NAV_GROUPS 的 code 复核
  function navAllowed(key) {
    var item = null;
    NAV_GROUPS.forEach(function (g) { g.items.forEach(function (it) { if (it.key === key) item = it; }); });
    return !item || hasPerm(identity, item.code);
  }
  function nav(key) {
    if (!navAllowed(key)) { window.message && window.message.error('无权限访问该页面'); return; }
    setPage(key); document.querySelector('.content-scroll') && (document.querySelector('.content-scroll').scrollTop = 0);
  }
  function setIdentity(key) { setTweak('identity', key); window.message.info('已切换身份：' + (PREVIEW_IDENTITIES[key] ? PREVIEW_IDENTITIES[key].roleLabel : key)); }

  // 渲染前派生有效页：当前 page 无权限时直接渲染概览，避免无权页先 mount 一帧（其 load() 会打「登录即可」的列表接口）。
  // effect 仍会把 page 状态修正为 overview，但渲染不依赖它先跑。
  var effectivePage = navAllowed(page) ? page : 'overview';
  var PageComp = PAGE_COMPONENTS[effectivePage] || OverviewPage;
  var pageProps = { identity: identity, onNav: nav, density: t.density === 'compact' ? 'compact' : 'regular', zebra: t.zebra };

  return React.createElement(IdentityCtx.Provider, { value: identity },
    booting ? React.createElement(BootSplash, null) :
    !logged ? React.createElement(LoginPage, { onLogin: doLogin }) :
    React.createElement('div', { className: 'app-shell' },
      React.createElement(Sidebar, { identity: identity, collapsed: t.siderCollapsed, active: effectivePage, onNav: nav }),
      React.createElement('div', { className: 'main-col' },
        React.createElement(Topbar, { identity: identity, active: effectivePage, collapsed: t.siderCollapsed,
          onToggleSider: function () { setTweak('siderCollapsed', !t.siderCollapsed); },
          onIdentity: setIdentity, onChangePwd: function () { setPwdOpen(true); }, onLogout: doLogout, realMode: !!me }),
        React.createElement('div', { className: 'content-scroll' },
          t.watermark ? React.createElement(Watermark, { identity: identity }) : null,
          React.createElement('div', { style: { position: 'relative', zIndex: 6 } },
            React.createElement(PageComp, pageProps))))),

    pwdOpen ? React.createElement(ChangePwdModal, { onClose: function () { setPwdOpen(false); } }) : null,
    React.createElement(OverlayHost, null),

    /* ---------- Tweaks 面板 ---------- */
    React.createElement(TweaksPanel, null,
      React.createElement(TweakSection, { label: '身份视角' }),
      React.createElement(TweakSelect, { label: '当前登录身份', value: t.identity,
        options: [
          { value: 'super', label: '超级管理员（陈国栋）' },
          { value: 'org', label: '组织部 · 林海燕' },
          { value: 'secretary', label: '秘书部 · 吴敏' },
          { value: 'publicity', label: '宣传部 · 黄梓萱' } ],
        onChange: function (v) { setIdentity(v); } }),
      React.createElement(TweakSection, { label: '外观' }),
      React.createElement(TweakColor, { label: '主强调色', value: t.primaryColor,
        options: ['#1677ff', '#2f54eb', '#13a8a8', '#c8102e', '#722ed1'],
        onChange: function (v) { setTweak('primaryColor', v); } }),
      React.createElement(TweakRadio, { label: '表格密度', value: t.density, options: ['compact', 'regular'],
        onChange: function (v) { setTweak('density', v); } }),
      React.createElement(TweakToggle, { label: '斑马纹表格', value: t.zebra, onChange: function (v) { setTweak('zebra', v); } }),
      React.createElement(TweakSection, { label: '布局与安全' }),
      React.createElement(TweakToggle, { label: '侧边栏折叠', value: t.siderCollapsed, onChange: function (v) { setTweak('siderCollapsed', v); } }),
      React.createElement(TweakToggle, { label: '防泄密水印', value: t.watermark, onChange: function (v) { setTweak('watermark', v); } })));
}

ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(App));
