/* ============================================================
   报名管理（进入某活动后）— 权限对照的核心示例页
   超管看到全部操作；组织部只看到「通过 / 拒绝」。
   ============================================================ */
function EnrollPage(props) {
  var id = props.identity;
  var actsWithEnroll = HD.ACTIVITIES.filter(function (a) { return HD.ENROLLMENTS[a.id]; });
  var [actId, setActId] = useState(actsWithEnroll[0] ? actsWithEnroll[0].id : null);
  var [tab, setTab] = useState('all');
  var [addOpen, setAddOpen] = useState(false);
  var act = HD.ACTIVITIES.filter(function (a) { return a.id === actId; })[0];
  var list = HD.ENROLLMENTS[actId] || [];

  var counts = { all: list.length, p0: list.filter(function (e) { return e.enrollStatus === 0; }).length,
    p1: list.filter(function (e) { return e.enrollStatus === 1; }).length, p2: list.filter(function (e) { return e.enrollStatus === 2; }).length };
  var filtered = list.filter(function (e) {
    if (tab === 'p0') return e.enrollStatus === 0; if (tab === 'p1') return e.enrollStatus === 1; if (tab === 'p2') return e.enrollStatus === 2; return true;
  });

  function approve(e) { window.message.success('已通过 ' + e.name + ' 的报名'); }
  function reject(e) {
    window.confirmDialog({ title: '拒绝 ' + e.name + ' 的报名？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true,
      reasonLabel: '拒绝原因', reasonPlaceholder: '将通知申请人，如：名额已满 / 资格不符' }).then(function (v) {
      if (v && v.ok) window.message.success('已拒绝并通知申请人');
    });
  }
  function del(e) {
    window.confirmDialog({ title: '删除 ' + e.name + ' 的报名记录？', danger: true, okText: '删除', content: '删除后无法恢复。' }).then(function (ok) { if (ok) window.message.success('已删除报名记录'); });
  }

  var cols = [
    { title: '志愿者', key: 'name', render: function (r) { return React.createElement(UserCell, { name: r.name, sub: r.school }); } },
    { title: '手机号', key: 'phone', width: 140, render: function (r) { return React.createElement('span', { className: 'mono' }, r.phone); } },
    { title: '报名来源', key: 'source', width: 130, render: function (r) {
      return r.source === '代报名' ? React.createElement('div', null, React.createElement(Tag, { color: 'purple' }, '代报名'),
        React.createElement('div', { className: 'cell-sub' }, '代报人：' + r.proxy)) : React.createElement(Tag, { color: 'default' }, '自报名');
    }},
    { title: '报名时间', key: 'time', width: 120, render: function (r) { return React.createElement('span', { className: 'cell-sub' }, r.time); } },
    { title: '状态', key: 'st', width: 110, render: function (r) {
      return React.createElement('div', null, React.createElement(StatusTag, { map: 'enroll', value: r.enrollStatus, dot: true }),
        r.reason ? React.createElement('div', { className: 'cell-sub', style: { maxWidth: 160 } }, r.reason) : null);
    }},
    { title: '操作', key: 'act', width: 150, render: function (r) {
      var canAudit = HD.hasPerm(id, 'activity:enroll-audit');
      var canDel = HD.hasPerm(id, 'activity:enroll-delete');
      if (!canAudit && !canDel) return React.createElement('span', { className: 'muted' }, '—');
      return React.createElement('div', { className: 'row-actions' },
        canAudit && r.enrollStatus === 0 ? React.createElement(React.Fragment, null,
          React.createElement('button', { className: 'btn-link', onClick: function () { approve(r); } }, '通过'),
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link danger', onClick: function () { reject(r); } }, '拒绝')) :
          (r.enrollStatus !== 0 ? React.createElement('span', { className: 'muted' }, '已处理') : null),
        canDel ? React.createElement(React.Fragment, null,
          (canAudit && r.enrollStatus === 0) ? React.createElement('span', { className: 'act-sep' }) : null,
          React.createElement('button', { className: 'btn-link danger', onClick: function () { del(r); } }, '删除')) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '报名管理', desc: '查看某活动的报名名单并审核。看到的操作按账号权限点显隐——这是「超管 vs 部门子账号」最直观的对照页。' }),
    React.createElement('div', { style: { display: 'flex', gap: 12, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' } },
      React.createElement('span', { style: { fontSize: 13, color: 'var(--text-2)' } }, '选择活动'),
      React.createElement(Select, { inline: true, minWidth: 320, value: actId, onChange: function (v) { setActId(v); setTab('all'); },
        options: actsWithEnroll.map(function (a) { return { value: a.id, label: a.title + '（' + a.date + '）' }; }) }),
      React.createElement('div', { style: { marginLeft: 'auto', display: 'flex', gap: 8 } },
        React.createElement(Auth, { code: 'activity:enroll-add' }, React.createElement(Btn, { icon: 'plus', onClick: function () { setAddOpen(true); } }, '手动新增报名')),
        React.createElement(Auth, { code: 'activity:enroll-export' }, React.createElement(Btn, { icon: 'download', onClick: function () { window.message.success('正在导出报名名单 Excel（demo）'); } }, '导出名单')))),

    !HD.hasPerm(id, 'activity:enroll-add') && !HD.hasPerm(id, 'activity:enroll-export') && !HD.hasPerm(id, 'activity:enroll-delete') ?
      React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } },
        '当前身份（' + id.roleLabel + '）仅持有 ',
        React.createElement('span', { className: 'mono' }, 'enroll-view + enroll-audit'),
        '：能看名单、能「通过/拒绝」，但看不到「手动新增 / 导出 / 删除」按钮（属 enroll-add / enroll-export / enroll-delete）。') : null,

    act ? React.createElement('div', { className: 'card', style: { marginBottom: 16 } },
      React.createElement('div', { className: 'card-body', style: { padding: '14px 20px', display: 'flex', gap: 28, alignItems: 'center', flexWrap: 'wrap' } },
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '活动'), React.createElement('div', { className: 'strong' }, act.title)),
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '时间'), React.createElement('div', null, act.date + ' ' + act.time)),
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '报名/名额'), React.createElement('div', null, act.enrolled + ' / ' + act.quota)),
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '运行状态'), React.createElement(StatusTag, { map: 'run', value: act.run, dot: true })))) : null,

    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        { key: 'all', label: '全部', count: counts.all }, { key: 'p0', label: '待审核', count: counts.p0 },
        { key: 'p1', label: '已通过', count: counts.p1 }, { key: 'p2', label: '已拒绝', count: counts.p2 } ] }),
      React.createElement(Table, { columns: cols, data: filtered, density: props.density, zebra: props.zebra,
        emptyText: tab === 'p0' ? '没有待审核的报名' : '暂无报名记录', pagination: false })),

    addOpen ? React.createElement(Drawer, { open: true, title: '手动新增报名', sub: '越权补录 · activity:enroll-add', onClose: function () { setAddOpen(false); },
      footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: function () { setAddOpen(false); } }, '取消'),
        React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已新增报名（demo）'); setAddOpen(false); } }, '确认新增')) },
      React.createElement(Alert, { type: 'warning', style: { marginBottom: 18 } }, '手动新增属越权补录，将直接计入名单并标记来源为「代报名」。'),
      React.createElement(Field, { label: '志愿者手机号', required: true }, React.createElement(Input, { placeholder: '输入手机号定位志愿者' })),
      React.createElement(Field, { label: '姓名', required: true }, React.createElement(Input, { placeholder: '志愿者姓名' })),
      React.createElement(Field, { label: '备注' }, React.createElement(Textarea, { placeholder: '补录原因', rows: 3 }))) : null);
}
window.EnrollPage = EnrollPage;
