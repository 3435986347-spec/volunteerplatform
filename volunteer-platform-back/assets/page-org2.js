/* ============================================================
   组织管理 · 子账号与权限（权限分配仅超管）/ 志愿者标记与授权（真实接口）
     子账号：GET/POST /a/organization/sub-accounts · GET|PUT|DELETE /{id} · POST /{id}/password/reset · PUT /{id}/permissions(超管)
     权限目录：GET /a/organization/permissions（含 id/code/name/module/type）· GET …/volunteer-grantable
     志愿者标记：GET /a/organization/volunteers/{id}/flag-info · PUT …/manager-flag · GET|PUT …/permissions(超管)
   注：子账号 status 0启用/1禁用；权限分配按 permission.id（前端 code↔id 经目录映射）。
   ============================================================ */

var PERM_MODULE_LABEL = { user: '用户管理', activity: '活动管理', org: '组织管理', publicity: '信息公示', data: '数据看板' };
function groupByModule(perms) {
  var g = {};
  perms.forEach(function (p) { (g[p.module] = g[p.module] || []).push(p); });
  return Object.keys(g).map(function (m) { return { module: m, label: PERM_MODULE_LABEL[m] || m, perms: g[m] }; });
}

/* ---------- 2. 子账号与权限 ---------- */
function SubAccountsPage(props) {
  var id = props.identity;
  var isSuper = !!id.isSuperAdmin;
  var [kw, setKw] = useState('');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [edit, setEdit] = useState(null);
  var [permOf, setPermOf] = useState(null);
  var [resetOf, setResetOf] = useState(null);
  var SIZE = 10;

  function load() {
    setLoading(true); setErr(false);
    API.get('/a/organization/sub-accounts', { keyword: kw, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [page, kw]);

  function del(a) {
    window.confirmDialog({ title: '删除子账号「' + (a.realName || a.username) + '」？', danger: true, okText: '删除', content: '删除后该账号无法登录后台。' }).then(function (ok) {
      if (ok) API.del('/a/organization/sub-accounts/' + a.id).then(function () { window.message.success('已删除'); load(); });
    });
  }

  var cols = [
    { title: '账号', key: 'account', width: 140, render: function (a) { return React.createElement('span', { className: 'mono' }, a.username); } },
    { title: '姓名', key: 'name', width: 120, render: function (a) { return React.createElement(UserCell, { name: a.realName || a.username, size: 'sm' }); } },
    { title: '部门', key: 'dept', width: 120, render: function (a) { return a.department ? React.createElement(Tag, { color: 'blue' }, a.department) : React.createElement('span', { className: 'muted' }, '—'); } },
    { title: '手机号', key: 'phone', width: 130, render: function (a) { return React.createElement('span', { className: 'mono' }, a.phone || '—'); } },
    { title: '状态', key: 'status', width: 90, render: function (a) { return React.createElement(StatusTag, { map: 'acct', value: a.status, dot: true }); } },
    { title: '创建时间', key: 'createTime', width: 120, render: function (a) { return React.createElement('span', { className: 'cell-sub' }, a.createTime ? String(a.createTime).slice(0, 10) : '—'); } },
    { title: '操作', key: 'act', width: 240, render: function (a) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setEdit({ mode: 'edit', data: a }); } }, '编辑'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link', disabled: !isSuper, title: isSuper ? '' : '仅超管可分配', onClick: function () { if (isSuper) setPermOf(a); } }, '分配权限'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement(Dropdown, { items: [
          { icon: 'key', label: '重置密码', onClick: function () { setResetOf(a); } },
          { divider: true },
          { icon: 'trash', label: '删除', danger: true, onClick: function () { del(a); } },
        ], trigger: React.createElement('button', { className: 'btn-link' }, '更多 ▾') }));
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '子账号与权限', desc: '部门子账号的增删改查与权限分配。权限分配仅超级管理员可操作。',
      actions: React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEdit({ mode: 'create' }); } }, '新增子账号') }),
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '「分配权限」仅超管可操作：当前身份 ', React.createElement('b', null, id.roleLabel || (isSuper ? '超级管理员' : '子账号')), '，', isSuper ? '可分配。' : '该按钮已禁用。'),
    React.createElement(Toolbar, { filters: React.createElement(Search, { value: kw, onChange: function (v) { setKw(v); setPage(1); }, placeholder: '搜索账号 / 姓名 / 部门', width: 260 }) }),
    React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
      pagination: { total: total, page: page, size: SIZE, onChange: setPage } }),

    edit ? React.createElement(SubAccountFormDrawer, { state: edit, onClose: function () { setEdit(null); }, onSaved: load }) : null,
    permOf ? React.createElement(PermissionDrawer, { account: permOf, onClose: function () { setPermOf(null); } }) : null,
    resetOf ? React.createElement(ResetPwdModal, { account: resetOf, onClose: function () { setResetOf(null); } }) : null);
}

function SubAccountFormDrawer(props) {
  var s = props.state, d = s.data || {}, isEdit = s.mode === 'edit';
  var [f, setF] = useState({ username: d.username || '', password: '', realName: d.realName || '', phone: d.phone || '', department: d.department || '' });
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }
  function save() {
    if (!isEdit && !f.username.trim()) { window.message.error('请填写登录账号'); return; }
    if (!isEdit && (f.password || '').length < 6) { window.message.error('初始密码至少 6 位'); return; }
    var req;
    if (isEdit) {
      req = API.put('/a/organization/sub-accounts/' + d.id, { realName: f.realName || null, phone: f.phone || null, department: f.department || null });
    } else {
      req = API.post('/a/organization/sub-accounts', { username: f.username.trim(), password: f.password, realName: f.realName || null, phone: f.phone || null, department: f.department || null });
    }
    setSaving(true);
    req.then(function () { window.message.success('已保存子账号'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: isEdit ? '编辑子账号' : '新增子账号', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: saving }, saving ? '保存中…' : '保存')) },
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '登录账号', required: !isEdit }, React.createElement(Input, { value: f.username, onChange: function (v) { set('username', v); }, disabled: isEdit, placeholder: '英文/数字' })),
      React.createElement(Field, { label: '姓名' }, React.createElement(Input, { value: f.realName, onChange: function (v) { set('realName', v); }, placeholder: '真实姓名' }))),
    isEdit ? null : React.createElement(Field, { label: '初始密码', required: true, hint: '6~32 位，首次登录后建议修改' }, React.createElement(Input, { type: 'password', value: f.password, onChange: function (v) { set('password', v); }, placeholder: '设置初始密码' })),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '部门' }, React.createElement(Input, { value: f.department, onChange: function (v) { set('department', v); }, placeholder: '如：组织部 / 秘书部' })),
      React.createElement(Field, { label: '手机号' }, React.createElement(Input, { value: f.phone, onChange: function (v) { set('phone', v); }, placeholder: '选填' }))),
    React.createElement(Alert, { type: 'info', style: { marginTop: 4 } }, '权限分配请在列表「分配权限」中进行（仅超管）。新建账号默认无任何权限点。账号状态（启用/停用）暂无修改接口。'));
}

function ResetPwdModal(props) {
  var a = props.account;
  var [pwd, setPwd] = useState('Hd@' + Math.random().toString(36).slice(2, 7));
  var [saving, setSaving] = useState(false);
  function submit() {
    if ((pwd || '').length < 6) { window.message.error('密码至少 6 位'); return; }
    setSaving(true);
    API.post('/a/organization/sub-accounts/' + a.id + '/password/reset', { newPassword: pwd })
      .then(function () { window.message.success('已重置，临时密码：' + pwd); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Modal, { open: true, title: '重置密码 · ' + (a.realName || a.username), onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit, disabled: saving }, saving ? '重置中…' : '确认重置')) },
    React.createElement(Field, { label: '新密码', required: true, hint: '6~32 位，重置后请告知该账号、首次登录修改', style: { marginBottom: 0 } },
      React.createElement(Input, { value: pwd, onChange: setPwd, placeholder: '新密码' })));
}

/* 权限分配抽屉（子账号）：按 module 分组勾选树，提交 permissionIds */
function PermissionDrawer(props) {
  var acc = props.account;
  var [catalog, setCatalog] = useState([]);
  var [checked, setChecked] = useState([]); // codes
  var [loading, setLoading] = useState(true);
  var [saving, setSaving] = useState(false);
  var [collapsed, setCollapsed] = useState({});
  useEffect(function () {
    Promise.all([API.get('/a/organization/permissions'), API.get('/a/organization/sub-accounts/' + acc.id)])
      .then(function (res) { setCatalog(res[0] || []); setChecked((res[1] && res[1].permissionCodes) || []); })
      .catch(function () {}).then(function () { setLoading(false); });
  }, []);
  function toggle(code) { setChecked(function (c) { return c.indexOf(code) >= 0 ? c.filter(function (x) { return x !== code; }) : c.concat([code]); }); }
  function toggleModule(perms, allSel) {
    var codes = perms.map(function (p) { return p.code; });
    setChecked(function (c) { return allSel ? c.filter(function (x) { return codes.indexOf(x) < 0; }) : c.concat(codes.filter(function (x) { return c.indexOf(x) < 0; })); });
  }
  function save() {
    var codeToId = {}; catalog.forEach(function (p) { codeToId[p.code] = p.id; });
    var ids = checked.map(function (c) { return codeToId[c]; }).filter(function (x) { return x != null; });
    setSaving(true);
    API.put('/a/organization/sub-accounts/' + acc.id + '/permissions', { permissionIds: ids })
      .then(function () { window.message.success('已保存权限'); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  var groups = groupByModule(catalog);
  return React.createElement(Drawer, { open: true, width: 'wide', title: '分配权限 · ' + (acc.realName || acc.username), sub: (acc.department || '') + ' · ' + acc.username + ' · 仅超管',
    onClose: props.onClose, footer: React.createElement(React.Fragment, null,
      React.createElement('span', { style: { marginRight: 'auto', fontSize: 13, color: 'var(--text-3)', alignSelf: 'center' } }, '已选 ' + checked.length + ' 个权限点'),
      React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: saving || loading }, saving ? '保存中…' : '保存权限')) },
    React.createElement(Alert, { type: 'warning', style: { marginBottom: 16 } }, '配权口诀：「审核权」往往要搭一个「查看/列表权」才闭合（如报名审核搭 enroll-view、分队加入审核搭 squad-manage）。'),
    loading ? React.createElement('div', { style: { padding: 24, color: 'var(--text-3)' } }, '加载中…') :
      React.createElement('div', { className: 'perm-tree' }, groups.map(function (m) {
        var codes = m.perms.map(function (p) { return p.code; });
        var selCount = codes.filter(function (c) { return checked.indexOf(c) >= 0; }).length;
        var allSel = selCount === codes.length, noneSel = selCount === 0;
        var col = collapsed[m.module];
        return React.createElement('div', { key: m.module, className: 'perm-module' },
          React.createElement('div', { className: 'perm-mod-head' },
            React.createElement(Checkbox, { checked: allSel, indeterminate: !allSel && !noneSel, onChange: function () { toggleModule(m.perms, allSel); } }),
            React.createElement('span', { className: 'pm-title', onClick: function () { setCollapsed(function (s) { var n = Object.assign({}, s); n[m.module] = !col; return n; }); }, style: { cursor: 'pointer' } }, m.label),
            React.createElement('span', { className: 'pm-count' }, selCount + ' / ' + codes.length),
            React.createElement('span', { onClick: function () { setCollapsed(function (s) { var n = Object.assign({}, s); n[m.module] = !col; return n; }); }, style: { cursor: 'pointer', color: 'var(--text-3)', display: 'flex' } }, React.createElement(Icon, { name: col ? 'chevronRight' : 'chevronDown', size: 15 }))),
          !col ? React.createElement('div', { className: 'perm-items' }, m.perms.map(function (p) {
            return React.createElement('div', { key: p.code, className: 'perm-item' },
              React.createElement(Checkbox, { checked: checked.indexOf(p.code) >= 0, onChange: function () { toggle(p.code); } }, React.createElement('span', null, p.name)),
              React.createElement('span', { className: 'pi-desc mono' }, p.code));
          })) : null);
      })));
}

/* ---------- 3. 志愿者标记与授权 ---------- */
function FlagPage(props) {
  var id = props.identity;
  var isSuper = !!id.isSuperAdmin;
  var [vid, setVid] = useState('');
  var [located, setLocated] = useState(null);  // {id,name,managerFlag,registered}
  var [granted, setGranted] = useState([]);    // PermissionVO[]
  var [loading, setLoading] = useState(false);
  var [grantOpen, setGrantOpen] = useState(false);

  function loadGranted(volId) {
    return API.get('/a/organization/volunteers/' + volId + '/permissions').then(function (g) { setGranted(g || []); }).catch(function () {});
  }
  function locate() {
    if (!/^\d+$/.test(String(vid).trim())) { window.message.warning('请输入志愿者数字 ID'); return; }
    var volId = String(vid).trim();
    setLoading(true);
    API.get('/a/organization/volunteers/' + volId + '/flag-info').then(function (info) {
      if (!info) { setLocated(null); window.message.error('未找到该志愿者'); return; }
      setLocated(info);
      if (isSuper) return loadGranted(info.id); // 已授权限仅超管可读（与授权写入口同边界）
    }).catch(function () {}).then(function () { setLoading(false); });
  }
  function toggleFlag(to) {
    API.put('/a/organization/volunteers/' + located.id + '/manager-flag', { flag: to })
      .then(function () { window.message.success(to ? '已标记为管理团队' : '已取消标记'); setLocated(Object.assign({}, located, { managerFlag: to })); if (!to) setGranted([]); });
  }

  return React.createElement('div', { className: 'page' },
    React.createElement(PageHead, { title: '志愿者标记与授权', desc: '管理团队志愿者下放活动域权限。先标记为「管理团队」再授权；授权仅超管，且只能勾选「可授权给志愿者」白名单。' }),
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '按志愿者 ID 定位（手机号/姓名搜索待「志愿者管理」user 模块上线后接入）。'),
    React.createElement('div', { className: 'card', style: { marginBottom: 16 } },
      React.createElement('div', { className: 'card-body', style: { display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' } },
        React.createElement(Field, { label: '志愿者 ID', style: { marginBottom: 0, flex: '1 1 280px' } },
          React.createElement(Input, { value: vid, onChange: setVid, placeholder: '输入志愿者数字 ID 定位' })),
        React.createElement(Btn, { type: 'primary', icon: 'search', onClick: locate, disabled: loading }, loading ? '定位中…' : '定位志愿者'))),

    located ? React.createElement('div', { className: 'card' },
      React.createElement('div', { className: 'card-head' },
        React.createElement(Avatar, { name: located.name || '志愿者' }),
        React.createElement('div', { style: { marginLeft: 4 } },
          React.createElement('h3', { style: { margin: 0 } }, located.name || ('志愿者 #' + located.id), located.managerFlag ? React.createElement(Tag, { color: 'purple', style: { marginLeft: 8 } }, '管理团队') : null),
          React.createElement('div', { className: 'sub' }, 'ID ' + located.id + (located.registered ? ' · 已实名' : ' · 未实名（游客）')))),
      React.createElement('div', { className: 'card-body' },
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px', background: 'var(--fill-1)', borderRadius: 8, marginBottom: 16 } },
          React.createElement('div', { style: { flex: 1 } }, React.createElement('div', { className: 'strong' }, '管理团队标记'), React.createElement('div', { className: 'cell-sub' }, '开启后可被授予活动域权限（标记需已实名）')),
          React.createElement(Auth, { code: 'org:manager-flag', fallback: React.createElement('span', { className: 'muted' }, '无标记权限') },
            React.createElement(Switch, { checked: located.managerFlag === 1, onChange: function (v) { toggleFlag(v ? 1 : 0); } }))),
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', marginBottom: 10 } },
          React.createElement('span', { className: 'strong' }, '已授权限'),
          React.createElement('span', { style: { marginLeft: 'auto' } },
            React.createElement(Btn, { size: 'sm', type: 'primary', icon: 'key', disabled: !isSuper || located.managerFlag !== 1, title: isSuper ? (located.managerFlag === 1 ? '' : '需先标记为管理团队') : '仅超管授权', onClick: function () { setGrantOpen(true); } }, '授权（仅超管）'))),
        !isSuper ? React.createElement('div', { className: 'muted', style: { fontSize: 13 } }, '授权信息仅超管可见') :
        granted.length ? React.createElement('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } }, granted.map(function (p) { return React.createElement(Tag, { key: p.id, color: 'blue' }, p.name); })) :
          React.createElement('div', { className: 'muted', style: { fontSize: 13 } }, located.managerFlag === 1 ? '尚未授予任何权限' : '需先标记为管理团队，再授权'))) :
      React.createElement('div', { className: 'card' }, React.createElement(EmptyState, { text: '请先定位志愿者', sub: '输入志愿者数字 ID，定位后可设置管理团队标记与授权。' })),

    grantOpen ? React.createElement(VolunteerGrantDrawer, { volunteer: located, onClose: function () { setGrantOpen(false); }, onSaved: function () { loadGranted(located.id); } }) : null);
}

function VolunteerGrantDrawer(props) {
  var v = props.volunteer;
  var [grantable, setGrantable] = useState([]);
  var [checked, setChecked] = useState([]); // permission ids
  var [loading, setLoading] = useState(true);
  var [saving, setSaving] = useState(false);
  useEffect(function () {
    Promise.all([API.get('/a/organization/permissions/volunteer-grantable'), API.get('/a/organization/volunteers/' + v.id + '/permissions')])
      .then(function (res) { setGrantable(res[0] || []); setChecked((res[1] || []).map(function (p) { return p.id; })); })
      .catch(function () {}).then(function () { setLoading(false); });
  }, []);
  function toggle(pid) { setChecked(function (c) { return c.indexOf(pid) >= 0 ? c.filter(function (x) { return x !== pid; }) : c.concat([pid]); }); }
  function save() {
    setSaving(true);
    API.put('/a/organization/volunteers/' + v.id + '/permissions', { permissionIds: checked })
      .then(function () { window.message.success('已保存授权'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: '授权给志愿者 · ' + (v.name || ('#' + v.id)), sub: '仅活动域白名单 · 仅超管', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null,
      React.createElement('span', { style: { marginRight: 'auto', fontSize: 13, color: 'var(--text-3)', alignSelf: 'center' } }, '已选 ' + checked.length + ' 项'),
      React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: saving || loading }, saving ? '保存中…' : '保存授权')) },
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '只列出「可授权给志愿者」的活动域权限点（后端白名单兜底；传空=清空授权）。'),
    loading ? React.createElement('div', { style: { padding: 24, color: 'var(--text-3)' } }, '加载中…') :
      grantable.length === 0 ? React.createElement(EmptyState, { text: '无可授权的权限点' }) :
      React.createElement('div', { style: { display: 'flex', flexDirection: 'column', gap: 6 } }, grantable.map(function (p) {
        return React.createElement('div', { key: p.id, style: { padding: '10px 12px', border: '1px solid var(--split)', borderRadius: 8 } },
          React.createElement(Checkbox, { checked: checked.indexOf(p.id) >= 0, onChange: function () { toggle(p.id); } },
            React.createElement('span', null, React.createElement('b', null, p.name), '　', React.createElement('span', { className: 'cell-sub mono' }, p.code))));
      })));
}

Object.assign(window, { SubAccountsPage: SubAccountsPage, FlagPage: FlagPage });
