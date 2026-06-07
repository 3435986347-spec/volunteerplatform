/* ============================================================
   组织管理 · 子账号与权限（仅超管核心）/ 志愿者标记与授权
   ============================================================ */

/* ---------- 2. 子账号与权限 ---------- */
function SubAccountsPage(props) {
  var id = props.identity;
  var [kw, setKw] = useState('');
  var [edit, setEdit] = useState(null);     // {mode, data}
  var [permOf, setPermOf] = useState(null);  // 分配权限抽屉（仅超管）
  var rows = HD.SUB_ACCOUNTS.filter(function (a) { return !kw || a.name.indexOf(kw) >= 0 || a.account.indexOf(kw) >= 0 || a.dept.indexOf(kw) >= 0; });

  function del(a) { window.confirmDialog({ title: '删除子账号「' + a.name + '」？', danger: true, okText: '删除', content: '删除后该账号无法登录后台。' }).then(function (ok) { if (ok) window.message.success('已删除'); }); }
  function resetPwd(a) { window.confirmDialog({ title: '重置 ' + a.name + ' 的密码？', content: '将生成临时密码并需其首次登录后修改。' }).then(function (ok) { if (ok) window.message.success('已重置，临时密码：Hd@2026'); }); }

  var cols = [
    { title: '账号', key: 'account', width: 130, render: function (a) { return React.createElement('span', { className: 'mono' }, a.account); } },
    { title: '姓名', key: 'name', width: 110, render: function (a) { return React.createElement(UserCell, { name: a.name, size: 'sm' }); } },
    { title: '部门', key: 'dept', width: 110, render: function (a) { return React.createElement(Tag, { color: 'blue' }, a.dept); } },
    { title: '已分配权限', key: 'codes', render: function (a) {
      return React.createElement('span', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.codes.length + ' 个权限点',
        React.createElement('span', { className: 'cell-sub' }, '　' + a.codes.slice(0, 2).map(function (c) { return permName(c); }).join('、') + (a.codes.length > 2 ? ' 等' : '')));
    }},
    { title: '状态', key: 'status', width: 90, render: function (a) { return React.createElement(StatusTag, { map: 'acct', value: a.status, dot: true }); } },
    { title: '创建时间', key: 'createTime', width: 120, render: function (a) { return React.createElement('span', { className: 'cell-sub' }, a.createTime); } },
    { title: '操作', key: 'act', width: 240, render: function (a) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setEdit({ mode: 'edit', data: a }); } }, '编辑'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link', onClick: function () { setPermOf(a); }, title: id.isSuperAdmin ? '' : '仅超管可分配' , disabled: !id.isSuperAdmin }, '分配权限'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement(Dropdown, { items: [
          { icon: 'key', label: '重置密码', onClick: function () { resetPwd(a); } },
          { divider: true },
          { icon: 'trash', label: '删除', danger: true, onClick: function () { del(a); } },
        ], trigger: React.createElement('button', { className: 'btn-link' }, '更多 ▾') }));
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '子账号与权限', desc: '部门子账号的增删改查与权限分配。权限分配仅超级管理员可操作。',
      actions: React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEdit({ mode: 'create' }); } }, '新增子账号') }),
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } },
      '「分配权限」标记为仅超管：当前身份为 ',
      React.createElement('b', null, id.roleLabel),
      '，',
      id.isSuperAdmin ? '可分配。' : '该按钮已禁用（无 super 权限）。'),
    React.createElement(Toolbar, { filters: React.createElement(Search, { value: kw, onChange: setKw, placeholder: '搜索账号 / 姓名 / 部门', width: 260 }) }),
    React.createElement(Table, { columns: cols, data: rows, density: props.density, zebra: props.zebra, pagination: false }),

    edit ? React.createElement(SubAccountFormDrawer, { state: edit, onClose: function () { setEdit(null); } }) : null,
    permOf ? React.createElement(PermissionDrawer, { account: permOf, onClose: function () { setPermOf(null); } }) : null);
}

function permName(code) {
  var found = null;
  HD.PERM_CATALOG.forEach(function (m) { m.perms.forEach(function (p) { if (p.code === code) found = p.name; }); });
  return found || code;
}

function SubAccountFormDrawer(props) {
  var s = props.state, d = s.data || {};
  return React.createElement(Drawer, { open: true, title: s.mode === 'edit' ? '编辑子账号' : '新增子账号', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已保存子账号'); props.onClose(); } }, '保存')) },
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '登录账号', required: true }, React.createElement(Input, { defaultValue: d.account, placeholder: '英文/数字' })),
      React.createElement(Field, { label: '姓名', required: true }, React.createElement(Input, { defaultValue: d.name, placeholder: '真实姓名' }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '部门', required: true }, React.createElement(Select, { defaultValue: d.dept, value: d.dept, options: ['组织部', '秘书部', '宣传部', '监察部', '财务部'].map(function (x) { return { value: x, label: x }; }), onChange: function () {} })),
      React.createElement(Field, { label: '状态' }, React.createElement(Select, { value: d.status != null ? d.status : 1, options: [{ value: 1, label: '启用' }, { value: 0, label: '停用' }], onChange: function () {} }))),
    s.mode === 'create' ? React.createElement(Field, { label: '初始密码', required: true, hint: '首次登录后需修改' }, React.createElement(Input, { type: 'password', placeholder: '设置初始密码' })) : null,
    React.createElement(Alert, { type: 'info', style: { marginTop: 4 } }, '权限分配请在列表「分配权限」中进行（仅超管）。新建账号默认无任何权限点。'));
}

/* ---------- 权限分配抽屉（按业务域分组的勾选树） ---------- */
function PermissionDrawer(props) {
  var acc = props.account;
  var [checked, setChecked] = useState(acc.codes.slice());
  var [collapsed, setCollapsed] = useState({});
  function toggle(code) { setChecked(function (c) { return c.indexOf(code) >= 0 ? c.filter(function (x) { return x !== code; }) : c.concat([code]); }); }
  function toggleModule(m, all) {
    var codes = m.perms.map(function (p) { return p.code; });
    setChecked(function (c) { return all ? c.filter(function (x) { return codes.indexOf(x) < 0; }) : c.concat(codes.filter(function (x) { return c.indexOf(x) < 0; })); });
  }
  return React.createElement(Drawer, { open: true, width: 'wide', title: '分配权限 · ' + acc.name, sub: acc.dept + ' · ' + acc.account + ' · 仅超管',
    onClose: props.onClose, footer: React.createElement(React.Fragment, null,
      React.createElement('span', { style: { marginRight: 'auto', fontSize: 13, color: 'var(--text-3)', alignSelf: 'center' } }, '已选 ' + checked.length + ' 个权限点'),
      React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已保存权限（PUT …/permissions）'); props.onClose(); } }, '保存权限')) },
    React.createElement(Alert, { type: 'warning', style: { marginBottom: 16 } }, '配权口诀：「审核权」往往要搭一个「查看/列表权」才闭合。例如报名审核需配 enroll-view；分队加入审核需配 squad-manage。'),
    React.createElement('div', { className: 'perm-tree' }, HD.PERM_CATALOG.map(function (m) {
      var codes = m.perms.map(function (p) { return p.code; });
      var selCount = codes.filter(function (c) { return checked.indexOf(c) >= 0; }).length;
      var allSel = selCount === codes.length, noneSel = selCount === 0;
      var col = collapsed[m.module];
      return React.createElement('div', { key: m.module, className: 'perm-module' },
        React.createElement('div', { className: 'perm-mod-head' },
          React.createElement(Checkbox, { checked: allSel, indeterminate: !allSel && !noneSel, onChange: function () { toggleModule(m, allSel); } }),
          React.createElement('span', { className: 'pm-title', onClick: function () { setCollapsed(function (s) { var n = Object.assign({}, s); n[m.module] = !col; return n; }); }, style: { cursor: 'pointer' } }, m.label),
          React.createElement('span', { className: 'pm-count' }, selCount + ' / ' + codes.length),
          React.createElement('span', { onClick: function () { setCollapsed(function (s) { var n = Object.assign({}, s); n[m.module] = !col; return n; }); }, style: { cursor: 'pointer', color: 'var(--text-3)', display: 'flex' } }, React.createElement(Icon, { name: col ? 'chevronRight' : 'chevronDown', size: 15 }))),
        !col ? React.createElement('div', { className: 'perm-items' }, m.perms.map(function (p) {
          return React.createElement('div', { key: p.code, className: 'perm-item' },
            React.createElement(Checkbox, { checked: checked.indexOf(p.code) >= 0, onChange: function () { toggle(p.code); } },
              React.createElement('span', null, p.name, p.superGrant ? React.createElement('span', { className: 'super-only-tag' }, '仅超管授权') : null)),
            React.createElement('span', { className: 'pi-desc' }, p.desc));
        })) : null);
    })));
}

/* ---------- 3. 志愿者标记与授权 ---------- */
function FlagPage(props) {
  var id = props.identity;
  var [vid, setVid] = useState('');
  var [located, setLocated] = useState(null);
  var [grantOf, setGrantOf] = useState(null);
  function locate() {
    if (!vid.trim()) { window.message.warning('请输入志愿者 ID 或手机号'); return; }
    var v = HD.FLAGGED_VOLUNTEERS.filter(function (x) { return String(x.id) === vid.trim() || x.phone === vid.trim(); })[0]
      || { id: vid.trim(), name: '待定位志愿者', phone: vid.trim(), school: '—', managerFlag: 0, grantedCodes: [] };
    setLocated(Object.assign({}, v));
  }
  return React.createElement('div', { className: 'page' },
    React.createElement(PageHead, { title: '志愿者标记与授权', desc: '管理团队志愿者下放活动域权限。志愿者检索可走 url 文档新增的 GET /a/user/volunteers（支持 keyword/分队/学校等筛选），此处以 ID / 手机号快速定位。' }),
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '需先将志愿者标记为「管理团队」，再为其授权。授权仅超管可操作，且只能从「可授权给志愿者」的活动域白名单中勾选。'),
    React.createElement('div', { className: 'card', style: { marginBottom: 16 } },
      React.createElement('div', { className: 'card-body', style: { display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' } },
        React.createElement(Field, { label: '志愿者 ID / 手机号', style: { marginBottom: 0, flex: '1 1 280px' }, hint: '示例可输入 9001 或 9004' },
          React.createElement(Input, { value: vid, onChange: setVid, placeholder: '输入志愿者 ID 或手机号定位' })),
        React.createElement(Btn, { type: 'primary', icon: 'search', onClick: locate }, '定位志愿者'))),

    located ? React.createElement('div', { className: 'card' },
      React.createElement('div', { className: 'card-head' },
        React.createElement(Avatar, { name: located.name }),
        React.createElement('div', { style: { marginLeft: 4 } },
          React.createElement('h3', { style: { margin: 0 } }, located.name, located.managerFlag ? React.createElement('span', { className: 'super-only-tag', style: { marginLeft: 8, color: '#531dab', background: '#f9f0ff', borderColor: '#d3adf7' } }, '管理团队') : null),
          React.createElement('div', { className: 'sub' }, 'ID ' + located.id + ' · ' + located.phone + ' · ' + located.school))),
      React.createElement('div', { className: 'card-body' },
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px', background: 'var(--fill-1)', borderRadius: 8, marginBottom: 16 } },
          React.createElement('div', { style: { flex: 1 } }, React.createElement('div', { className: 'strong' }, '管理团队标记'), React.createElement('div', { className: 'cell-sub' }, '开启后可被授予活动域权限')),
          React.createElement(Auth, { code: 'org:manager-flag', fallback: React.createElement('span', { className: 'muted' }, '无标记权限') },
            React.createElement(Switch, { checked: !!located.managerFlag, onChange: function (v) { setLocated(Object.assign({}, located, { managerFlag: v ? 1 : 0 })); window.message.success(v ? '已标记为管理团队' : '已取消标记'); } }))),
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', marginBottom: 10 } },
          React.createElement('span', { className: 'strong' }, '已授权限'),
          React.createElement('span', { style: { marginLeft: 'auto' } },
            React.createElement(Btn, { size: 'sm', type: 'primary', icon: 'key', disabled: !id.isSuperAdmin || !located.managerFlag, title: id.isSuperAdmin ? (located.managerFlag ? '' : '需先标记为管理团队') : '仅超管授权', onClick: function () { setGrantOf(located); } }, '授权（仅超管）'))),
        located.grantedCodes.length ? React.createElement('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } }, located.grantedCodes.map(function (c) { return React.createElement(Tag, { key: c, color: 'blue' }, permName(c)); })) :
          React.createElement('div', { className: 'muted', style: { fontSize: 13 } }, located.managerFlag ? '尚未授予任何权限' : '需先标记为管理团队，再授权'))) :
      React.createElement('div', { className: 'card' }, React.createElement(EmptyState, { text: '请先定位志愿者', sub: '输入志愿者 ID 或手机号，定位后可设置管理团队标记与授权。' })),

    grantOf ? React.createElement(Drawer, { open: true, title: '授权给志愿者 · ' + grantOf.name, sub: '仅活动域白名单 · 仅超管', onClose: function () { setGrantOf(null); },
      footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: function () { setGrantOf(null); } }, '取消'),
        React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已授权（PUT …/permissions）'); setGrantOf(null); } }, '保存授权')) },
      React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '只列出「可授权给志愿者」的活动域权限点（后端白名单兜底）。'),
      React.createElement('div', { style: { display: 'flex', flexDirection: 'column', gap: 6 } }, HD.VOLUNTEER_GRANTABLE.map(function (c) {
        return React.createElement('div', { key: c, style: { padding: '10px 12px', border: '1px solid var(--split)', borderRadius: 8 } },
          React.createElement(Checkbox, { checked: grantOf.grantedCodes.indexOf(c) >= 0, onChange: function () {} },
            React.createElement('span', null, React.createElement('b', null, permName(c)), '　', React.createElement('span', { className: 'cell-sub mono' }, c))));
      }))) : null);
}

Object.assign(window, { SubAccountsPage: SubAccountsPage, FlagPage: FlagPage, permName: permName });
