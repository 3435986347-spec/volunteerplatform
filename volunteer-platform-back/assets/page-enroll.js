/* ============================================================
   报名管理（真实接口，按活动维度）— 权限对照核心页
     GET    /a/activity/activities/{id}/enrollments?status=  名单（enroll-view）
     POST   /a/activity/activities/{id}/enrollments          手动新增 {volunteerId,slotIds}（enroll-add）
     GET    /a/activity/activities/{id}/enrollments/export    导出 Excel（enroll-export）
     POST   /a/activity/enrollments/{id}/approve|reject       审核（enroll-audit）
     DELETE /a/activity/enrollments/{id}                      删除（enroll-delete）
   注：行=按时间段(slot)的报名记录；status 0待审/1已通过/2已拒绝/3已取消。fmtRange 复用 page-activities.js。
   选活动用 GET /a/activity/activities（activity:menu）；若无该权限则回退手动输入活动 ID。
   ============================================================ */
function EnrollPage(props) {
  var id = props.identity;
  var canView = hasPerm(id, 'activity:enroll-view');
  var canAudit = hasPerm(id, 'activity:enroll-audit');
  var canDel = hasPerm(id, 'activity:enroll-delete');
  var [acts, setActs] = useState([]);
  var [actsErr, setActsErr] = useState(false);
  var [manualId, setManualId] = useState('');
  var [actId, setActId] = useState(null);
  var [tab, setTab] = useState('all');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [addOpen, setAddOpen] = useState(false);
  var SIZE = 10;

  useEffect(function () {
    if (!canView) return;
    API.get('/a/activity/activities', { page: 1, size: 100 }).then(function (res) {
      var rows = res.records || []; setActs(rows);
      if (rows.length) setActId(rows[0].id);
    }).catch(function () { setActsErr(true); }); // 无 activity:menu → 回退手动输入活动 ID
  }, []);

  function load() {
    if (!actId) { setList([]); setTotal(0); return; }
    setLoading(true); setErr(false);
    API.get('/a/activity/activities/' + actId + '/enrollments', { status: tab === 'all' ? '' : tab, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [actId, tab, page]);

  var act = acts.filter(function (a) { return a.id === actId; })[0];

  function approve(e) {
    API.post('/a/activity/enrollments/' + e.enrollmentId + '/approve').then(function () { window.message.success('已通过 ' + (e.realName || '') + ' 的报名'); load(); });
  }
  function reject(e) {
    window.confirmDialog({ title: '拒绝 ' + (e.realName || '') + ' 的报名？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝原因', reasonPlaceholder: '将通知申请人，如：名额已满 / 资格不符' }).then(function (v) {
      if (v && v.ok) API.post('/a/activity/enrollments/' + e.enrollmentId + '/reject', { reason: v.reason }).then(function () { window.message.success('已拒绝并通知申请人'); load(); });
    });
  }
  function del(e) {
    window.confirmDialog({ title: '删除 ' + (e.realName || '') + ' 的报名记录？', danger: true, okText: '删除', content: '删除后无法恢复。' }).then(function (ok) {
      if (ok) API.del('/a/activity/enrollments/' + e.enrollmentId).then(function () { window.message.success('已删除报名记录'); load(); });
    });
  }
  function exportList() {
    if (!actId) return;
    // 走 API.download（带 Authorization、处理 401、JSON 错误先解析），不用裸 window.open/fetch
    API.download('/a/activity/activities/' + actId + '/enrollments/export', '报名名单_' + actId + '.xlsx')
      .then(function () { window.message.success('已导出报名名单'); }).catch(function () {});
  }

  var cols = [
    { title: '志愿者', key: 'name', render: function (r) { return React.createElement(UserCell, { name: r.realName || '—', sub: r.school || '' }); } },
    { title: '手机号', key: 'phone', width: 140, render: function (r) { return React.createElement('span', { className: 'mono' }, r.phone || '—'); } },
    { title: '报名时间段', key: 'slot', width: 200, render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, r.projectName || '默认场次'),
        React.createElement('div', { className: 'cell-sub' }, fmtRange(r.slotStartTime, r.slotEndTime)));
    }},
    { title: '报名来源', key: 'source', width: 130, render: function (r) {
      return r.proxyByName ? React.createElement('div', null, React.createElement(Tag, { color: 'purple' }, '代报名'),
        React.createElement('div', { className: 'cell-sub' }, '代报人：' + r.proxyByName)) : React.createElement(Tag, { color: 'default' }, '自报名');
    }},
    { title: '报名时间', key: 'time', width: 120, render: function (r) { return React.createElement('span', { className: 'cell-sub' }, r.enrollTime ? String(r.enrollTime).slice(0, 16) : '—'); } },
    { title: '状态', key: 'st', width: 120, render: function (r) {
      return React.createElement('div', null, React.createElement(StatusTag, { map: 'enroll', value: r.status, dot: true }),
        r.rejectReason ? React.createElement('div', { className: 'cell-sub', style: { maxWidth: 160 } }, r.rejectReason) : null);
    }},
    { title: '操作', key: 'act', width: 150, render: function (r) {
      if (!canAudit && !canDel) return React.createElement('span', { className: 'muted' }, '—');
      return React.createElement('div', { className: 'row-actions' },
        (canAudit && r.status === 0) ? React.createElement(React.Fragment, null,
          React.createElement('button', { className: 'btn-link', onClick: function () { approve(r); } }, '通过'),
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link danger', onClick: function () { reject(r); } }, '拒绝')) :
          (r.status !== 0 ? React.createElement('span', { className: 'muted' }, '已处理') : null),
        canDel ? React.createElement(React.Fragment, null,
          (canAudit && r.status === 0) ? React.createElement('span', { className: 'act-sep' }) : null,
          React.createElement('button', { className: 'btn-link danger', onClick: function () { del(r); } }, '删除')) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '报名管理', desc: '选择活动查看其报名名单并审核。操作按账号权限点显隐（通过/拒绝=enroll-audit，手动新增=enroll-add，导出=enroll-export，删除=enroll-delete）。' }),
    !canView ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '你没有「报名查看」(activity:enroll-view) 权限。') : null,
    actsErr ? React.createElement(Alert, { type: 'warning', style: { marginBottom: 16 } }, '无法加载活动列表（可能缺 activity:menu 权限）。可直接输入活动 ID 管理其报名。') : null,

    canView ? React.createElement('div', { style: { display: 'flex', gap: 12, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' } },
      React.createElement('span', { style: { fontSize: 13, color: 'var(--text-2)' } }, '选择活动'),
      acts.length ? React.createElement(Select, { inline: true, minWidth: 320, value: acts.some(function (a) { return a.id === actId; }) ? actId : undefined, onChange: function (v) { setActId(v); setTab('all'); setPage(1); },
        options: acts.map(function (a) { return { value: a.id, label: a.title + '（' + datePart(a.startTime) + '）' }; }) }) : null,
      // 手动 ID 始终保留：活动超过 100 条（下拉只取前 100）或无 activity:menu 时仍可加载
      React.createElement(Input, { value: manualId, onChange: setManualId, placeholder: '或输入活动 ID', style: { width: 150 } }),
      React.createElement(Btn, { onClick: function () { if (String(manualId).trim()) { setActId(String(manualId).trim()); setTab('all'); setPage(1); } } }, '加载'),
      React.createElement('div', { style: { marginLeft: 'auto', display: 'flex', gap: 8 } },
        React.createElement(Auth, { code: 'activity:enroll-add' }, React.createElement(Btn, { icon: 'plus', disabled: !actId, onClick: function () { setAddOpen(true); } }, '手动新增报名')),
        React.createElement(Auth, { code: 'activity:enroll-export' }, React.createElement(Btn, { icon: 'download', disabled: !actId, onClick: exportList }, '导出名单')))) : null,

    (canView && act) ? React.createElement('div', { className: 'card', style: { marginBottom: 16 } },
      React.createElement('div', { className: 'card-body', style: { padding: '14px 20px', display: 'flex', gap: 28, alignItems: 'center', flexWrap: 'wrap' } },
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '活动'), React.createElement('div', { className: 'strong' }, act.title)),
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '时间'), React.createElement('div', null, fmtRange(act.startTime, act.endTime))),
        React.createElement('div', null, React.createElement('div', { className: 'cell-sub' }, '报名记录'), React.createElement('div', null, total + ' 条')))) : null,

    canView ? React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: [
        { key: 'all', label: '全部' }, { key: '0', label: '待审核' }, { key: '1', label: '已通过' }, { key: '2', label: '已拒绝' } ] }),
      React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
        pagination: { total: total, page: page, size: SIZE, onChange: setPage },
        emptyText: actId ? (tab === '0' ? '没有待审核的报名' : '暂无报名记录') : '请先选择活动' })) : null,

    addOpen ? React.createElement(ManualEnrollDrawer, { actId: actId, onClose: function () { setAddOpen(false); }, onSaved: load }) : null);
}

/* ---------- 手动新增报名抽屉（越权补录 · activity:enroll-add） ---------- */
function ManualEnrollDrawer(props) {
  var [slots, setSlots] = useState([]);
  var [slotsLoading, setSlotsLoading] = useState(true);
  var [slotsErr, setSlotsErr] = useState(false);
  var [vid, setVid] = useState('');
  var [chosen, setChosen] = useState([]);
  var [manualSlots, setManualSlots] = useState('');
  var [saving, setSaving] = useState(false);
  useEffect(function () {
    setSlotsLoading(true); setSlotsErr(false);
    // 报名域时间段接口（enroll-view），不需 activity:menu
    API.get('/a/activity/activities/' + props.actId + '/enrollment-slots')
      .then(function (res) { setSlots(res || []); })
      .catch(function () { setSlotsErr(true); }).then(function () { setSlotsLoading(false); });
  }, []);
  function toggle(sid) { setChosen(function (c) { return c.indexOf(sid) >= 0 ? c.filter(function (x) { return x !== sid; }) : c.concat([sid]); }); }
  function submit() {
    if (!/^\d+$/.test(String(vid).trim())) { window.message.error('请填写志愿者 ID（数字）'); return; }
    var slotIds = slotsErr
      ? manualSlots.split(/[,\s]+/).map(function (s) { return s.trim(); }).filter(function (s) { return /^\d+$/.test(s); })
      : chosen;
    if (!slotIds.length) { window.message.error('请至少选择 / 填写一个时间段'); return; }
    setSaving(true);
    // slotIds 原样传字符串（Long 序列化为字符串），后端 Jackson 转 Long，避免大数精度丢失
    API.post('/a/activity/activities/' + props.actId + '/enrollments', { volunteerId: String(vid).trim(), slotIds: slotIds })
      .then(function () { window.message.success('已新增报名'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  var slotField;
  if (slotsLoading) slotField = React.createElement('span', { className: 'muted' }, '加载时间段中…');
  else if (slotsErr) slotField = React.createElement(React.Fragment, null,
    React.createElement(Input, { value: manualSlots, onChange: setManualSlots, placeholder: '时间段 ID，逗号分隔，如 1001,1002' }),
    React.createElement('div', { className: 'cell-sub', style: { marginTop: 4 } }, '时间段加载失败，请手动填写时间段 ID'));
  else if (slots.length) slotField = React.createElement('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } }, slots.map(function (sl) {
    return React.createElement('div', { key: sl.id, className: 'radio-btn' + (chosen.indexOf(sl.id) >= 0 ? ' on' : ''), style: { borderRadius: 6, marginLeft: 0 }, onClick: function () { toggle(sl.id); } },
      (sl.projectName || '默认场次') + ' · ' + fmtRange(sl.startTime, sl.endTime));
  }));
  else slotField = React.createElement('span', { className: 'muted' }, '该活动无时间段');
  return React.createElement(Drawer, { open: true, title: '手动新增报名', sub: '越权补录 · activity:enroll-add', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit, disabled: saving }, saving ? '提交中…' : '确认新增')) },
    React.createElement(Alert, { type: 'warning', style: { marginBottom: 18 } }, '手动新增属越权补录，将跳过资格/截止校验（仍拦禁用账号），直接计入名单。'),
    React.createElement(Field, { label: '志愿者 ID', required: true, hint: '暂用志愿者 ID；按手机号/姓名搜索待「志愿者管理」(user 模块) 上线后接入' },
      React.createElement(Input, { value: vid, onChange: setVid, placeholder: '志愿者 ID（数字）' })),
    React.createElement(Field, { label: '报名时间段', required: true }, slotField));
}

window.EnrollPage = EnrollPage;
