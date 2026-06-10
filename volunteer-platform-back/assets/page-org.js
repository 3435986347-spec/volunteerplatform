/* ============================================================
   组织管理 · 志愿小组 / 归属分队（真实接口 /a/organization/*）
   小组：GET /groups?keyword= · DELETE /{id}(解散) · PUT /{id}/leader(转移) · GET /{id}/leader-history ·
         GET /{id}/members(转移选人) · POST /import(Excel) · GET /applications(建组申请) · POST /applications/{id}/approve|reject
   分队：GET/POST /squads · PUT|DELETE /{id} · GET /applications?status=(全局待审) · GET /{id}/applications · POST /applications/{id}/approve|reject
   状态码：GroupStatus 0待审/1正常/2拒绝/3解散；MemberRole 0普通/1组长/2管理员；MemberStatus 1在册。
   ============================================================ */

/* ---------- 4. 志愿小组 ---------- */
function GroupsPage(props) {
  var id = props.identity;
  var canManage = hasPerm(id, 'org:group-manage');
  var canAudit = hasPerm(id, 'org:group-audit');
  var [tab, setTab] = useState(canManage ? 'list' : 'apply');
  var [kw, setKw] = useState('');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [apps, setApps] = useState([]);
  var [appsLoading, setAppsLoading] = useState(false);
  var [appsErr, setAppsErr] = useState(false);
  var [historyOf, setHistoryOf] = useState(null);
  var [transferOf, setTransferOf] = useState(null);
  var [importOpen, setImportOpen] = useState(false);
  var SIZE = 10;

  function loadList() {
    setLoading(true); setErr(false);
    API.get('/a/organization/groups', { keyword: kw, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  function loadApps() {
    setAppsLoading(true); setAppsErr(false);
    API.get('/a/organization/groups/applications', { page: 1, size: 100 })
      .then(function (res) { setApps(res.records || []); }).catch(function () { setAppsErr(true); }).then(function () { setAppsLoading(false); });
  }
  useEffect(function () { if (tab === 'list' && canManage) loadList(); }, [tab, page, kw]);
  useEffect(function () { if (tab === 'apply' && canAudit) loadApps(); }, [tab]);

  function dissolve(g) {
    window.confirmDialog({ title: '解散小组「' + g.name + '」？', danger: true, okText: '确认解散', reason: true, reasonRequired: true, reasonLabel: '解散原因', content: '解散后成员将被移出该小组，操作不可逆。' }).then(function (v) {
      if (v && v.ok) API.del('/a/organization/groups/' + g.id, { reason: v.reason }).then(function () { window.message.success('已解散小组'); loadList(); });
    });
  }
  function approveApp(a) {
    window.confirmDialog({ title: '批准建组「' + a.name + '」？', content: '批准后该小组生效，申请人成为组长。' }).then(function (ok) {
      if (ok) API.post('/a/organization/groups/applications/' + a.id + '/approve').then(function () { window.message.success('已批准建组'); loadApps(); });
    });
  }
  function rejectApp(a) {
    window.confirmDialog({ title: '拒绝建组申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝原因' }).then(function (v) {
      if (v && v.ok) API.post('/a/organization/groups/applications/' + a.id + '/reject', { reason: v.reason }).then(function () { window.message.success('已拒绝'); loadApps(); });
    });
  }

  var listCols = [
    { title: '小组名称', key: 'name', render: function (g) { return React.createElement('div', null, React.createElement('span', { className: 'strong' }, g.name), g.groupNo ? React.createElement('div', { className: 'cell-sub' }, g.groupNo) : null); } },
    { title: '组长', key: 'leader', width: 130, render: function (g) { return g.leaderName || '—'; } },
    { title: '成员数', key: 'members', width: 90, align: 'center', render: function (g) { return (g.memberCount != null ? g.memberCount : 0) + ' 人'; } },
    { title: '状态', key: 'status', width: 100, render: function (g) { return React.createElement(StatusTag, { map: 'groupStatus', value: g.status, dot: true }); } },
    { title: '创建时间', key: 'time', width: 130, render: function (g) { return React.createElement('span', { className: 'cell-sub' }, g.createTime ? String(g.createTime).slice(0, 16) : '—'); } },
    { title: '操作', key: 'act', width: 200, render: function (g) {
      if (!canManage) return React.createElement('span', { className: 'muted' }, '仅查看');
      // 仅 ACTIVE(1) 可转移/解散；待审(0)/拒绝(2)/解散(3) 只看历史（后端 transfer/dissolve 也要求 ACTIVE）
      if (g.status !== 1) return React.createElement('button', { className: 'btn-link', onClick: function () { setHistoryOf(g); } }, '组长历史');
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setTransferOf(g); } }, '转移组长'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link', onClick: function () { setHistoryOf(g); } }, '历史'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link danger', onClick: function () { dissolve(g); } }, '解散'));
    }},
  ];

  var appsBody;
  if (appsLoading) appsBody = React.createElement('div', { style: { padding: 32, textAlign: 'center', color: 'var(--text-3)' } }, '加载中…');
  else if (appsErr) appsBody = React.createElement('div', { style: { padding: 32, textAlign: 'center' } }, React.createElement(Btn, { onClick: loadApps }, '加载失败，重试'));
  else if (apps.length === 0) appsBody = React.createElement(EmptyState, { text: '暂无待审建组申请' });
  else appsBody = React.createElement(React.Fragment, null, apps.map(function (a) {
    return React.createElement('div', { key: a.id, className: 'card', style: { marginBottom: 12, boxShadow: 'none', border: '1px solid var(--split)' } },
      React.createElement('div', { className: 'card-body', style: { display: 'flex', gap: 16, alignItems: 'flex-start' } },
        React.createElement('span', { className: 'sc-icon', style: { background: 'var(--primary-bg)', color: 'var(--primary)', width: 40, height: 40, borderRadius: 10, flex: '0 0 40px', display: 'flex', alignItems: 'center', justifyContent: 'center' } }, React.createElement(Icon, { name: 'team', size: 20 })),
        React.createElement('div', { style: { flex: 1, minWidth: 0 } },
          React.createElement('div', { style: { fontWeight: 600, fontSize: 15, marginBottom: 4 } }, a.name),
          React.createElement('div', { className: 'cell-sub', style: { marginBottom: 6 } }, '申请人：' + (a.leaderName || '—') + (a.createTime ? ' · ' + String(a.createTime).slice(0, 16) : '')),
          a.description ? React.createElement('div', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.description) : null),
        canAudit ? React.createElement('div', { style: { display: 'flex', gap: 8, flex: '0 0 auto' } },
          React.createElement(Btn, { danger: true, onClick: function () { rejectApp(a); } }, '拒绝'),
          React.createElement(Btn, { type: 'primary', onClick: function () { approveApp(a); } }, '批准')) :
          React.createElement('span', { className: 'muted' }, '无审批权限')));
  }));

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '志愿小组', desc: '小组全量管理与建组审批。建组申请走全局接口，单 group-audit 即可审批。',
      actions: canManage ? React.createElement(Btn, { icon: 'import', onClick: function () { setImportOpen(true); } }, '批量导入') : null }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        canManage ? { key: 'list', label: '全部小组' } : null,
        canAudit ? { key: 'apply', label: '建组申请' } : null,
      ].filter(Boolean) }),
      (tab === 'list' && canManage) ? React.createElement('div', null,
        React.createElement('div', { style: { padding: '12px 16px 0' } }, React.createElement(Search, { value: kw, onChange: function (v) { setKw(v); setPage(1); }, placeholder: '搜索小组名 / 组长', width: 240 })),
        React.createElement('div', { style: { padding: 16 } }, React.createElement(Table, { columns: listCols, data: list, loading: loading, error: err, onRetry: loadList, density: props.density, zebra: props.zebra,
          pagination: { total: total, page: page, size: SIZE, onChange: setPage } }))) :
        React.createElement('div', { style: { padding: 16 } }, appsBody)),

    historyOf ? React.createElement(GroupHistoryDrawer, { group: historyOf, onClose: function () { setHistoryOf(null); } }) : null,
    transferOf ? React.createElement(TransferLeaderModal, { group: transferOf, onClose: function () { setTransferOf(null); }, onSaved: loadList }) : null,
    importOpen ? React.createElement(GroupImportDrawer, { onClose: function () { setImportOpen(false); }, onSaved: loadList }) : null);
}

function GroupHistoryDrawer(props) {
  var g = props.group;
  var [items, setItems] = useState([]);
  var [loading, setLoading] = useState(true);
  useEffect(function () {
    API.get('/a/organization/groups/' + g.id + '/leader-history')
      .then(function (res) { setItems(res || []); }).catch(function () {}).then(function () { setLoading(false); });
  }, []);
  var body = loading ? React.createElement('div', { style: { padding: 24, color: 'var(--text-3)' } }, '加载中…') :
    items.length === 0 ? React.createElement(EmptyState, { text: '暂无组长变更记录' }) :
    React.createElement(Timeline, { items: items.map(function (h) {
      return { time: h.changeTime ? String(h.changeTime).slice(0, 16) : '', body: React.createElement('div', null,
        h.oldLeaderName ? React.createElement('span', null, React.createElement('b', null, h.oldLeaderName), ' → ', React.createElement('b', null, h.newLeaderName)) : React.createElement('span', null, '首任组长 ', React.createElement('b', null, h.newLeaderName)),
        h.reason ? React.createElement('span', { className: 'by' }, '　· ' + h.reason) : null) };
    }) });
  return React.createElement(Drawer, { open: true, title: '组长变更历史 · ' + g.name, onClose: props.onClose, footer: null }, body);
}

function TransferLeaderModal(props) {
  var g = props.group;
  var [members, setMembers] = useState([]);
  var [vid, setVid] = useState('');
  var [reason, setReason] = useState('');
  var [saving, setSaving] = useState(false);
  useEffect(function () {
    API.get('/a/organization/groups/' + g.id + '/members')
      .then(function (res) { setMembers((res || []).filter(function (m) { return m.status === 1 && m.role !== 1; })); }).catch(function () {});
  }, []);
  function submit() {
    if (!vid) { window.message.error('请选择新组长'); return; }
    setSaving(true);
    API.put('/a/organization/groups/' + g.id + '/leader', { volunteerId: vid, reason: reason || null })
      .then(function () { window.message.success('已转移组长'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Modal, { open: true, title: '转移组长 · ' + g.name, onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit, disabled: saving }, saving ? '转移中…' : '确认转移')) },
    React.createElement(Field, { label: '当前组长' }, React.createElement(Input, { value: g.leaderName || '', disabled: true })),
    React.createElement(Field, { label: '新组长', required: true }, React.createElement(Select, { value: vid, onChange: setVid, placeholder: members.length ? '从在册成员中选择' : '无可选在册成员',
      options: members.map(function (m) { return { value: m.volunteerId, label: m.realName + (m.role === 2 ? '（管理员）' : '') }; }) })),
    React.createElement(Field, { label: '变更原因', style: { marginBottom: 0 } }, React.createElement(Input, { value: reason, onChange: setReason, placeholder: '可选，写入组长变更历史' })));
}

function GroupImportDrawer(props) {
  var [file, setFile] = useState(null);
  var [saving, setSaving] = useState(false);
  function submit() {
    if (!file) { window.message.error('请选择 Excel 文件'); return; }
    var fd = new FormData(); fd.append('file', file);
    setSaving(true);
    API.post('/a/organization/groups/import', fd)
      .then(function (n) { window.message.success('导入完成' + (n != null ? '，共 ' + n + ' 个小组' : '')); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: '批量导入小组', sub: 'POST /a/organization/groups/import · Excel', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit, disabled: saving }, saving ? '导入中…' : '开始导入')) },
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '上传 Excel 文件批量建组（仅建 ACTIVE 小组，每行须含组长信息）。'),
    React.createElement(DropUpload, { onFile: setFile, accept: '.xlsx,.xls', icon: 'import', main: file ? file.name : '点击或拖拽 Excel 文件', hint: '仅支持 .xlsx，单文件' }));
}

/* ---------- 5. 归属分队 ---------- */
function SquadsPage(props) {
  var id = props.identity;
  var canManage = hasPerm(id, 'org:squad-manage');
  var canAudit = hasPerm(id, 'org:squad-audit');
  var [list, setList] = useState([]);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [appOf, setAppOf] = useState(null);
  var [editOf, setEditOf] = useState(null);
  var [globalOpen, setGlobalOpen] = useState(false);

  function load() {
    if (!canManage) return;   // 仅 squad-audit 的账号无 list 权限，走「全部待审加入」
    setLoading(true); setErr(false);
    API.get('/a/organization/squads', { page: 1, size: 100 })
      .then(function (res) { setList(res.records || []); }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);

  function del(s) {
    window.confirmDialog({ title: '删除分队「' + s.name + '」？', danger: true, okText: '删除' }).then(function (ok) {
      if (ok) API.del('/a/organization/squads/' + s.id).then(function () { window.message.success('已删除分队'); load(); });
    });
  }

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '归属分队', desc: '分队增删改查与加入审批。全局待审接口（每行带 squadName）可统一审批，无需逐分队进入。',
      actions: React.createElement(React.Fragment, null,
        canAudit ? React.createElement(Btn, { icon: 'inbox', onClick: function () { setGlobalOpen(true); } }, '全部待审加入') : null,
        canManage ? React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEditOf({}); } }, '新建分队') : null) }),
    (!canManage && canAudit) ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '你持有 org:squad-audit：可经「全部待审加入」直接审批，无需 squad-manage 先列分队。下方分队卡片的管理操作（新建/编辑/删除）需 squad-manage。') : null,

    canManage ? (
      err ? React.createElement('div', { style: { padding: 24 } }, React.createElement(Btn, { onClick: load }, '加载失败，重试')) :
      loading ? React.createElement('div', { style: { padding: 24, color: 'var(--text-3)' } }, '加载中…') :
      list.length === 0 ? React.createElement(EmptyState, { text: '暂无分队' }) :
      React.createElement('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 16 } },
        list.map(function (s) {
          var cap = s.memberLimit || 0, mc = s.memberCount != null ? s.memberCount : 0;
          var pct = cap > 0 ? Math.round(mc / cap * 100) : 0;
          return React.createElement('div', { key: s.id, className: 'card' },
            React.createElement('div', { className: 'card-body' },
              React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 } },
                React.createElement('span', { className: 'sc-icon', style: { background: 'var(--primary-bg)', color: 'var(--primary)', width: 38, height: 38, borderRadius: 10, display: 'flex', alignItems: 'center', justifyContent: 'center' } }, React.createElement(Icon, { name: 'squad', size: 18 })),
                React.createElement('div', { style: { flex: 1, minWidth: 0 } },
                  React.createElement('div', { className: 'strong', style: { fontSize: 15 } }, s.name),
                  React.createElement('div', { className: 'cell-sub' }, '队长：' + (s.leaderName || '—') + (s.type ? ' · ' + s.type : '')))),
              React.createElement('div', { style: { display: 'flex', justifyContent: 'space-between', fontSize: 13, color: 'var(--text-2)', marginBottom: 6 } },
                React.createElement('span', null, '成员 ' + mc + ' / ' + (cap || '—')), React.createElement('span', { className: 'muted' }, cap > 0 ? pct + '%' : '')),
              React.createElement('div', { style: { height: 6, background: 'var(--fill-3)', borderRadius: 3, overflow: 'hidden', marginBottom: 14 } },
                React.createElement('div', { style: { width: pct + '%', height: '100%', background: pct >= 90 ? 'var(--warning)' : 'var(--primary)' } })),
              React.createElement('div', { style: { display: 'flex', gap: 6, justifyContent: 'flex-end' } },
                canAudit ? React.createElement(Btn, { size: 'sm', type: 'text', className: 'primary', onClick: function () { setAppOf(s); } }, '加入申请') : null,
                canManage ? React.createElement(Btn, { size: 'sm', type: 'text', icon: 'edit', onClick: function () { setEditOf(s); } }, '编辑') : null,
                canManage ? React.createElement(Btn, { size: 'sm', type: 'text', danger: true, onClick: function () { del(s); } }, '删除') : null)));
        }))
    ) : null,

    globalOpen ? React.createElement(SquadAppsDrawer, { title: '全部待审分队加入', sub: 'GET /a/organization/squads/applications?status=0', showSquadName: true, canAudit: canAudit,
      fetcher: function () { return API.get('/a/organization/squads/applications', { status: 0, page: 1, size: 100 }); }, onClose: function () { setGlobalOpen(false); } }) : null,
    appOf ? React.createElement(SquadAppsDrawer, { title: '加入申请 · ' + appOf.name, sub: 'GET /a/organization/squads/' + appOf.id + '/applications', showSquadName: false, canAudit: canAudit,
      fetcher: function () { return API.get('/a/organization/squads/' + appOf.id + '/applications', { page: 1, size: 100 }); }, onClose: function () { setAppOf(null); } }) : null,
    editOf ? React.createElement(SquadFormDrawer, { squad: editOf, onClose: function () { setEditOf(null); }, onSaved: load }) : null);
}

/* 分队加入申请列表（全局/单分队共用），SquadApplicationVO：volunteerName/squadName/reason/status/applyTime */
function SquadAppsDrawer(props) {
  var [rows, setRows] = useState([]);
  var [loading, setLoading] = useState(true);
  var [err, setErr] = useState(false);
  function load() {
    setLoading(true); setErr(false);
    props.fetcher().then(function (res) { setRows(res.records || []); }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);
  function approve(a) {
    window.confirmDialog({ title: '批准 ' + (a.volunteerName || '') + ' 加入' + (a.squadName ? '「' + a.squadName + '」' : '') + '？' }).then(function (ok) {
      if (ok) API.post('/a/organization/squads/applications/' + a.id + '/approve').then(function () { window.message.success('已批准加入'); load(); });
    });
  }
  function reject(a) {
    window.confirmDialog({ title: '拒绝该加入申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝原因' }).then(function (v) {
      if (v && v.ok) API.post('/a/organization/squads/applications/' + a.id + '/reject', { reason: v.reason }).then(function () { window.message.success('已拒绝'); load(); });
    });
  }
  var body = loading ? React.createElement('div', { style: { padding: 24, color: 'var(--text-3)' } }, '加载中…') :
    err ? React.createElement('div', { style: { padding: 24 } }, React.createElement(Btn, { onClick: load }, '加载失败，重试')) :
    rows.length === 0 ? React.createElement(EmptyState, { text: '暂无加入申请' }) :
    rows.map(function (a) {
      return React.createElement('div', { key: a.id, style: { display: 'flex', gap: 12, alignItems: 'flex-start', padding: '14px 0', borderBottom: '1px solid var(--split)' } },
        React.createElement(Avatar, { name: a.volunteerName || '—' }),
        React.createElement('div', { style: { flex: 1, minWidth: 0 } },
          React.createElement('div', { className: 'strong' }, a.volunteerName || ('志愿者#' + a.volunteerId), props.showSquadName && a.squadName ? React.createElement(React.Fragment, null, '　', React.createElement(Tag, { color: 'cyan' }, a.squadName)) : null),
          React.createElement('div', { className: 'cell-sub', style: { margin: '2px 0 4px' } }, a.applyTime ? String(a.applyTime).slice(0, 16) : ''),
          a.reason ? React.createElement('div', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.reason) : null),
        (props.canAudit && a.status === 0) ? React.createElement('div', { style: { display: 'flex', gap: 6, flex: '0 0 auto' } },
          React.createElement(Btn, { size: 'sm', danger: true, onClick: function () { reject(a); } }, '拒绝'),
          React.createElement(Btn, { size: 'sm', type: 'primary', onClick: function () { approve(a); } }, '批准')) :
          (a.status !== 0 ? React.createElement('span', { className: 'muted' }, '已处理') : null));
    });
  return React.createElement(Drawer, { open: true, width: 'wide', title: props.title, sub: props.sub, onClose: props.onClose, footer: null },
    props.showSquadName ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '全局聚合所有分队的待审加入申请，每行标注所属分队，可在一处统一审批。') : null,
    body);
}

function SquadFormDrawer(props) {
  var s = props.squad || {};
  var isEdit = !!s.id;
  var [f, setF] = useState({ name: s.name || '', type: s.type || '常规', leaderName: s.leaderName || '', leaderPhone: s.leaderPhone || '', memberLimit: s.memberLimit != null ? s.memberLimit : 50 });
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }
  function save() {
    if (!f.name) { window.message.error('请填写分队名称'); return; }
    if (!f.type) { window.message.error('请填写分队类型'); return; }
    var body = { name: f.name, type: f.type, leaderName: f.leaderName || null, leaderPhone: f.leaderPhone || null, memberLimit: Number(f.memberLimit) || null };
    if (isEdit) {
      // 后端 update 为全量拷贝：编辑须带回未在表单上的字段，否则 leaderId/visibleFields/status 被清空。
      // 新建不发这些，走后端默认语义（启用/默认可见字段）。
      body.leaderId = s.leaderId != null ? s.leaderId : null;
      body.visibleFields = s.visibleFields || null;
      body.status = s.status != null ? s.status : null;
    }
    var req = isEdit ? API.put('/a/organization/squads/' + s.id, body) : API.post('/a/organization/squads', body);
    setSaving(true);
    req.then(function () { window.message.success('已保存'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: isEdit ? '编辑分队' : '新建分队', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: saving }, saving ? '保存中…' : '保存')) },
    React.createElement(Field, { label: '分队名称', required: true }, React.createElement(Input, { value: f.name, onChange: function (v) { set('name', v); }, placeholder: '如：城区第三分队' })),
    React.createElement(Field, { label: '分队类型', required: true }, React.createElement(Input, { value: f.type, onChange: function (v) { set('type', v); }, placeholder: '如：常规 / 应急 / 专项' })),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '队长' }, React.createElement(Input, { value: f.leaderName, onChange: function (v) { set('leaderName', v); }, placeholder: '队长姓名' })),
      React.createElement(Field, { label: '队长电话' }, React.createElement(Input, { value: f.leaderPhone, onChange: function (v) { set('leaderPhone', v); }, placeholder: '联系电话' }))),
    React.createElement(Field, { label: '人数上限', style: { marginBottom: 0 } }, React.createElement(Input, { type: 'number', value: f.memberLimit, onChange: function (v) { set('memberLimit', v); } })));
}

Object.assign(window, { GroupsPage: GroupsPage, SquadsPage: SquadsPage });
