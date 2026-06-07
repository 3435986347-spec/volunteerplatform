/* ============================================================
   组织管理 · 志愿小组 / 归属分队
   ============================================================ */

/* ---------- 4. 志愿小组 ---------- */
function GroupsPage(props) {
  var id = props.identity;
  var [tab, setTab] = useState('list');
  var [kw, setKw] = useState('');
  var [historyOf, setHistoryOf] = useState(null);
  var [transferOf, setTransferOf] = useState(null);
  var [importOpen, setImportOpen] = useState(false);
  var canManage = HD.hasPerm(id, 'org:group-manage');
  var canAudit = HD.hasPerm(id, 'org:group-audit');

  var groups = HD.GROUPS.filter(function (g) { return !kw || g.name.indexOf(kw) >= 0 || g.leader.indexOf(kw) >= 0; });
  var apps = HD.GROUP_APPLICATIONS;

  function dissolve(g) { window.confirmDialog({ title: '解散小组「' + g.name + '」？', danger: true, okText: '确认解散', reason: true, reasonRequired: true, reasonLabel: '解散原因', content: '解散后成员将被移出该小组，操作不可逆。' }).then(function (v) { if (v && v.ok) window.message.success('已解散小组'); }); }
  function approveApp(a) { window.confirmDialog({ title: '批准建组「' + a.name + '」？', content: '批准后该小组生效，申请人成为组长。' }).then(function (ok) { if (ok) window.message.success('已批准建组'); }); }
  function rejectApp(a) { window.confirmDialog({ title: '拒绝建组申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true }).then(function (v) { if (v && v.ok) window.message.success('已拒绝'); }); }

  var listCols = [
    { title: '小组名称', key: 'name', render: function (g) { return React.createElement('span', { className: 'strong' }, g.name); } },
    { title: '组长', key: 'leader', width: 150, render: function (g) { return React.createElement(UserCell, { name: g.leader, sub: g.leaderPhone, size: 'sm' }); } },
    { title: '成员数', key: 'members', width: 90, align: 'center', render: function (g) { return g.members + ' 人'; } },
    { title: '状态', key: 'status', width: 100, render: function (g) { return React.createElement(StatusTag, { map: 'groupStatus', value: g.status, dot: true }); } },
    { title: '创建/审批', key: 'time', width: 150, render: function (g) { return React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '建：' + g.createTime), React.createElement('div', { className: 'cell-sub' }, '批：' + g.approveTime)); } },
    { title: '操作', key: 'act', width: 200, render: function (g) {
      if (!canManage) return React.createElement('span', { className: 'muted' }, '仅查看');
      if (g.status === 2) return React.createElement('button', { className: 'btn-link', onClick: function () { setHistoryOf(g); } }, '组长历史');
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setTransferOf(g); } }, '转移组长'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link', onClick: function () { setHistoryOf(g); } }, '历史'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link danger', onClick: function () { dissolve(g); } }, '解散'));
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '志愿小组', desc: '小组全量管理与建组审批。建组申请走全局接口，单 group-audit 即可审批。',
      actions: canManage ? React.createElement(Btn, { icon: 'import', onClick: function () { setImportOpen(true); } }, '批量导入') : null }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        canManage ? { key: 'list', label: '全部小组', count: HD.GROUPS.length } : null,
        canAudit ? { key: 'apply', label: '建组申请', count: apps.filter(function (a) { return a.status === 0; }).length } : null,
      ].filter(Boolean) }),
      (tab === 'list' && canManage) ? React.createElement('div', null,
        React.createElement('div', { style: { padding: '12px 16px 0' } }, React.createElement(Search, { value: kw, onChange: setKw, placeholder: '搜索小组名 / 组长', width: 240 })),
        React.createElement('div', { style: { padding: 16 } }, React.createElement(Table, { columns: listCols, data: groups, density: props.density, zebra: props.zebra, pagination: false }))) :
      React.createElement('div', { style: { padding: 16 } },
        apps.length === 0 ? React.createElement(EmptyState, { text: '暂无待审建组申请' }) :
        apps.map(function (a) {
          return React.createElement('div', { key: a.id, className: 'card', style: { marginBottom: 12, boxShadow: 'none', border: '1px solid var(--split)' } },
            React.createElement('div', { className: 'card-body', style: { display: 'flex', gap: 16, alignItems: 'flex-start' } },
              React.createElement('span', { className: 'sc-icon', style: { background: 'var(--primary-bg)', color: 'var(--primary)', width: 40, height: 40, borderRadius: 10, flex: '0 0 40px', display: 'flex', alignItems: 'center', justifyContent: 'center' } }, React.createElement(Icon, { name: 'team', size: 20 })),
              React.createElement('div', { style: { flex: 1, minWidth: 0 } },
                React.createElement('div', { style: { fontWeight: 600, fontSize: 15, marginBottom: 4 } }, a.name, '　', React.createElement(Tag, { color: 'blue' }, '拟招 ' + a.members + ' 人')),
                React.createElement('div', { className: 'cell-sub', style: { marginBottom: 6 } }, '申请人：' + a.applicant + ' · ' + a.phone + ' · ' + a.school + ' · ' + a.time),
                React.createElement('div', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.reason)),
              canAudit ? React.createElement('div', { style: { display: 'flex', gap: 8, flex: '0 0 auto' } },
                React.createElement(Btn, { danger: true, onClick: function () { rejectApp(a); } }, '拒绝'),
                React.createElement(Btn, { type: 'primary', onClick: function () { approveApp(a); } }, '批准')) :
                React.createElement('span', { className: 'muted' }, '无审批权限')));
        }))),

    historyOf ? React.createElement(Drawer, { open: true, title: '组长变更历史 · ' + historyOf.name, onClose: function () { setHistoryOf(null); }, footer: null },
      React.createElement(Timeline, { items: (HD.GROUP_LEADER_HISTORY[historyOf.id] || [{ time: historyOf.approveTime, body: '建组通过，' + historyOf.leader + ' 任组长' }]).map(function (h) {
        return { time: h.time, body: React.createElement('div', null, h.from ? React.createElement('span', null, React.createElement('b', null, h.from), ' → ', React.createElement('b', null, h.to)) : React.createElement('span', null, '首任组长 ', React.createElement('b', null, h.to)), React.createElement('span', { className: 'by' }, '　· ' + h.by)) };
      }) })) : null,
    transferOf ? React.createElement(Modal, { open: true, title: '转移组长 · ' + transferOf.name, onClose: function () { setTransferOf(null); },
      footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: function () { setTransferOf(null); } }, '取消'),
        React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已转移组长'); setTransferOf(null); } }, '确认转移')) },
      React.createElement(Field, { label: '当前组长' }, React.createElement(Input, { value: transferOf.leader, disabled: true })),
      React.createElement(Field, { label: '新组长', required: true, style: { marginBottom: 0 } }, React.createElement(Select, { placeholder: '从成员中选择', options: ['刘子墨', '黄思远', '陈嘉怡'].map(function (n) { return { value: n, label: n }; }) }))) : null,
    importOpen ? React.createElement(Drawer, { open: true, title: '批量导入小组', sub: 'POST /a/organization/groups/import · Excel', onClose: function () { setImportOpen(false); },
      footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: function () { setImportOpen(false); } }, '取消'), React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('导入完成（demo）'); setImportOpen(false); } }, '开始导入')) },
      React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '唯一的 multipart 端点：上传 Excel 文件批量建组。请先下载模板按格式填写。'),
      React.createElement('div', { style: { marginBottom: 16 } }, React.createElement(Btn, { icon: 'download', size: 'sm' }, '下载导入模板.xlsx')),
      React.createElement(DropUpload, { icon: 'import', main: '点击或拖拽 Excel 文件', hint: '仅支持 .xlsx，单文件' })) : null);
}

/* ---------- 5. 归属分队 ---------- */
function SquadsPage(props) {
  var id = props.identity;
  var [appOf, setAppOf] = useState(null);
  var [editOf, setEditOf] = useState(null);
  var [globalOpen, setGlobalOpen] = useState(false);
  var canManage = HD.hasPerm(id, 'org:squad-manage');
  var canAudit = HD.hasPerm(id, 'org:squad-audit');
  var globalPending = 0; Object.keys(HD.SQUAD_APPLICATIONS).forEach(function (k) { HD.SQUAD_APPLICATIONS[k].forEach(function (a) { if (a.status === 0) globalPending++; }); });

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '归属分队', desc: '分队增删改查与加入审批。url 文档已补全局待审接口 GET /a/organization/squads/applications（每行带 squadName），可统一审批，无需逐分队进入。',
      actions: React.createElement(React.Fragment, null,
        canAudit ? React.createElement(Btn, { icon: 'inbox', onClick: function () { setGlobalOpen(true); } },
          '全部待审加入', globalPending ? React.createElement('span', { className: 'count-pill', style: { marginLeft: 2 } }, globalPending) : null) : null,
        canManage ? React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEditOf({}); } }, '新建分队') : null) }),
    !canManage && canAudit ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '你持有 org:squad-audit：可经「全部待审加入」走全局接口 GET /a/organization/squads/applications 直接审批，无需 squad-manage 先列分队。下方分队卡片的管理操作（新建/编辑/删除）仍需 squad-manage。') : null,
    React.createElement('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 16 } },
      HD.SQUADS.map(function (s) {
        var pct = Math.round(s.members / s.cap * 100);
        return React.createElement('div', { key: s.id, className: 'card' },
          React.createElement('div', { className: 'card-body' },
            React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 } },
              React.createElement('span', { className: 'sc-icon', style: { background: 'var(--primary-bg)', color: 'var(--primary)', width: 38, height: 38, borderRadius: 10, display: 'flex', alignItems: 'center', justifyContent: 'center' } }, React.createElement(Icon, { name: 'squad', size: 18 })),
              React.createElement('div', { style: { flex: 1, minWidth: 0 } }, React.createElement('div', { className: 'strong', style: { fontSize: 15 } }, s.name), React.createElement('div', { className: 'cell-sub' }, '队长：' + s.captain)),
              s.pending > 0 ? React.createElement('span', { className: 'count-pill' }, s.pending) : null),
            React.createElement('div', { style: { display: 'flex', justifyContent: 'space-between', fontSize: 13, color: 'var(--text-2)', marginBottom: 6 } },
              React.createElement('span', null, '成员 ' + s.members + ' / ' + s.cap), React.createElement('span', { className: 'muted' }, pct + '%')),
            React.createElement('div', { style: { height: 6, background: 'var(--fill-3)', borderRadius: 3, overflow: 'hidden', marginBottom: 14 } },
              React.createElement('div', { style: { width: pct + '%', height: '100%', background: pct >= 90 ? 'var(--warning)' : 'var(--primary)' } })),
            React.createElement('div', { style: { display: 'flex', gap: 6, justifyContent: 'flex-end' } },
              canAudit ? React.createElement(Btn, { size: 'sm', type: 'text', className: 'primary', onClick: function () { setAppOf(s); } }, '加入申请' + (s.pending ? '(' + s.pending + ')' : '')) : null,
              canManage ? React.createElement(Btn, { size: 'sm', type: 'text', icon: 'edit', onClick: function () { setEditOf(s); } }, '编辑') : null,
              canManage ? React.createElement(Btn, { size: 'sm', type: 'text', danger: true, onClick: function () { window.confirmDialog({ title: '删除分队「' + s.name + '」？', danger: true, okText: '删除' }).then(function (ok) { if (ok) window.message.success('已删除分队'); }); } }, '删除') : null)));
      })),

    globalOpen ? React.createElement(GlobalSquadAppsDrawer, { canAudit: canAudit, onClose: function () { setGlobalOpen(false); } }) : null,
    appOf ? React.createElement(SquadAppDrawer, { squad: appOf, canAudit: canAudit, onClose: function () { setAppOf(null); } }) : null,
    editOf ? React.createElement(Drawer, { open: true, title: editOf.id ? '编辑分队' : '新建分队', onClose: function () { setEditOf(null); },
      footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: function () { setEditOf(null); } }, '取消'),
        React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已保存'); setEditOf(null); } }, '保存')) },
      React.createElement(Field, { label: '分队名称', required: true }, React.createElement(Input, { defaultValue: editOf.name, placeholder: '如：城区第三分队' })),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '队长' }, React.createElement(Input, { defaultValue: editOf.captain, placeholder: '队长姓名' })),
        React.createElement(Field, { label: '人数上限' }, React.createElement(Input, { type: 'number', defaultValue: editOf.cap || 50 })))) : null);
}
function GlobalSquadAppsDrawer(props) {
  // 聚合全部分队的待审加入申请（对应全局接口，每行带 squadName）
  var rows = [];
  HD.SQUADS.forEach(function (s) {
    (HD.SQUAD_APPLICATIONS[s.id] || []).forEach(function (a) {
      if (a.status === 0) rows.push(Object.assign({ squadName: s.name, squadId: s.id }, a));
    });
  });
  function approve(a) { window.confirmDialog({ title: '批准 ' + a.name + ' 加入「' + a.squadName + '」？' }).then(function (ok) { if (ok) window.message.success('已批准加入'); }); }
  function reject(a) { window.confirmDialog({ title: '拒绝该加入申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true }).then(function (v) { if (v && v.ok) window.message.success('已拒绝'); }); }
  return React.createElement(Drawer, { open: true, width: 'wide', title: '全部待审分队加入', sub: 'GET /a/organization/squads/applications?status=0', onClose: props.onClose, footer: null },
    React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '全局聚合所有分队的待审加入申请，每行标注所属分队（squadName），可在一处统一审批。'),
    rows.length === 0 ? React.createElement(EmptyState, { text: '暂无待审加入申请' }) :
    rows.map(function (a) {
      return React.createElement('div', { key: a.id, style: { display: 'flex', gap: 12, alignItems: 'flex-start', padding: '14px 0', borderBottom: '1px solid var(--split)' } },
        React.createElement(Avatar, { name: a.name }),
        React.createElement('div', { style: { flex: 1, minWidth: 0 } },
          React.createElement('div', { className: 'strong' }, a.name, '　', React.createElement(Tag, { color: 'cyan' }, a.squadName), '　', React.createElement('span', { className: 'cell-sub mono' }, a.phone)),
          React.createElement('div', { className: 'cell-sub', style: { margin: '2px 0 4px' } }, a.school + ' · ' + a.time),
          React.createElement('div', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.reason)),
        props.canAudit ? React.createElement('div', { style: { display: 'flex', gap: 6, flex: '0 0 auto' } },
          React.createElement(Btn, { size: 'sm', danger: true, onClick: function () { reject(a); } }, '拒绝'),
          React.createElement(Btn, { size: 'sm', type: 'primary', onClick: function () { approve(a); } }, '批准')) : null);
    }));
}
function SquadAppDrawer(props) {
  var s = props.squad;
  var apps = HD.SQUAD_APPLICATIONS[s.id] || [];
  function approve(a) { window.confirmDialog({ title: '批准 ' + a.name + ' 加入「' + s.name + '」？' }).then(function (ok) { if (ok) window.message.success('已批准加入'); }); }
  function reject(a) { window.confirmDialog({ title: '拒绝该加入申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true }).then(function (v) { if (v && v.ok) window.message.success('已拒绝'); }); }
  return React.createElement(Drawer, { open: true, title: '加入申请 · ' + s.name, sub: 'GET /a/organization/squads/' + s.id + '/applications', onClose: props.onClose, footer: null },
    apps.length === 0 ? React.createElement(EmptyState, { text: '暂无加入申请' }) :
    apps.map(function (a) {
      return React.createElement('div', { key: a.id, style: { display: 'flex', gap: 12, alignItems: 'flex-start', padding: '14px 0', borderBottom: '1px solid var(--split)' } },
        React.createElement(Avatar, { name: a.name }),
        React.createElement('div', { style: { flex: 1, minWidth: 0 } },
          React.createElement('div', { className: 'strong' }, a.name, '　', React.createElement('span', { className: 'cell-sub mono' }, a.phone)),
          React.createElement('div', { className: 'cell-sub', style: { margin: '2px 0 4px' } }, a.school + ' · ' + a.time),
          React.createElement('div', { style: { fontSize: 13, color: 'var(--text-2)' } }, a.reason)),
        props.canAudit ? React.createElement('div', { style: { display: 'flex', gap: 6, flex: '0 0 auto' } },
          React.createElement(Btn, { size: 'sm', danger: true, onClick: function () { reject(a); } }, '拒绝'),
          React.createElement(Btn, { size: 'sm', type: 'primary', onClick: function () { approve(a); } }, '批准')) : null);
    }));
}

Object.assign(window, { GroupsPage: GroupsPage, SquadsPage: SquadsPage });
