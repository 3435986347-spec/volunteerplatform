/* ============================================================
   服务记录与积分（秘书部）/ 考勤变更审核 / 活动补录审核（真实接口）
     服务记录：GET /a/activity/service-records?secretaryStatus=  （大板块，登录即可）
               POST /a/activity/attendances/{attendanceId}/confirm（service-confirm）
               POST /a/activity/attendances/{attendanceId}/points {pointsFactor 0正常/1减半/2不发}（points-grant）
     考勤变更：GET /a/activity/attendance-changes?status=  + POST …/{id}/approve|reject {reason}（attendance-audit）
     活动补录：GET /a/activity/backfills?status=           + POST …/{id}/approve|reject {reason}（backfill-audit）
   注：confirm/grant 作用于 attendanceId（非 service-record id）；积分由后端按 基数×角色倍率×系数 计算。
   datePart/fmtRange 复用 page-activities.js 全局函数。
   ============================================================ */
function fmtDur(min) { min = Number(min) || 0; var h = Math.floor(min / 60), m = min % 60; return h + ' 小时' + (m ? ' ' + m + ' 分' : ''); }
function changeTypeLabel(t) { return t === 1 ? '签到时间' : t === 2 ? '签退时间' : t === 3 ? '积分' : '变更'; }
function chgVal(t, v) { if (v == null || v === '') return '—'; return (t === 1 || t === 2) ? String(v).replace('T', ' ').slice(0, 16) : String(v); }

/* ---------- 10. 服务记录与积分 ---------- */
function ServicePage(props) {
  var id = props.identity;
  var canConfirm = hasPerm(id, 'activity:service-confirm');
  var canGrant = hasPerm(id, 'activity:points-grant');
  var [tab, setTab] = useState(canConfirm ? 'pending' : 'all'); // 'pending' 待确认（可确认列表） | 'all' 全部
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [pointsRow, setPointsRow] = useState(null);
  var SIZE = 10;

  function load() {
    setLoading(true); setErr(false);
    // 待确认走 /pending（仅已签退、可确认时长的行；board?secretaryStatus=0 会含未签退/请假缺席等不可确认行）
    var req = tab === 'pending'
      ? API.get('/a/activity/service-records/pending', { page: page, size: SIZE })
      : API.get('/a/activity/service-records', { page: page, size: SIZE });
    req.then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab, page]);

  function confirm(r) {
    window.confirmDialog({ title: '确认 ' + (r.volunteerName || '') + ' 的服务时长（' + fmtDur(r.serviceMinutes) + '）？', content: '确认后可发放积分。' }).then(function (ok) {
      if (ok) API.post('/a/activity/attendances/' + r.attendanceId + '/confirm').then(function () { window.message.success('已确认服务时长'); load(); });
    });
  }

  var cols = [
    { title: '志愿者', key: 'name', width: 110, render: function (r) { return React.createElement('span', { className: 'strong' }, r.volunteerName || '—'); } },
    { title: '活动', key: 'activity', render: function (r) { return r.activityTitle || '—'; } },
    { title: '服务日期', key: 'date', width: 110, render: function (r) { return React.createElement('span', { className: 'cell-sub' }, datePart(r.checkInTime) || '—'); } },
    { title: '服务时长', key: 'hours', width: 110, align: 'center', render: function (r) { return fmtDur(r.serviceMinutes); } },
    { title: '确认状态', key: 'sec', width: 100, render: function (r) { return React.createElement(StatusTag, { map: 'secretary', value: r.secretaryStatus, dot: true }); } },
    { title: '积分', key: 'points', width: 80, align: 'center', render: function (r) { return React.createElement('span', { style: { color: r.pointsAward > 0 ? 'var(--success)' : 'var(--text-3)', fontWeight: 600 } }, r.pointsAward != null ? '+' + r.pointsAward : '—'); } },
    { title: '发放状态', key: 'ps', width: 100, render: function (r) { return React.createElement(StatusTag, { map: 'points', value: r.pointsStatus, dot: true }); } },
    { title: '操作', key: 'act', width: 150, render: function (r) {
      var canDoConfirm = canConfirm && r.secretaryStatus === 0;
      var canDoGrant = canGrant && r.secretaryStatus === 1 && r.pointsStatus === 0;
      if (!canDoConfirm && !canDoGrant) return React.createElement('span', { className: 'muted' }, r.pointsStatus === 1 ? '已完成' : '—');
      return React.createElement('div', { className: 'row-actions' },
        canDoConfirm ? React.createElement('button', { className: 'btn-link', onClick: function () { confirm(r); } }, '确认时长') : null,
        canDoGrant ? React.createElement('button', { className: 'btn-link', onClick: function () { setPointsRow(r); } }, '发放积分') : null);
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '服务记录与积分', desc: '秘书部确认已签退志愿者的服务时长，并按规则发放积分（可正常/减半/不发；积分由后端按基数×角色倍率×系数计算）。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: canConfirm ?
        [{ key: 'pending', label: '待确认' }, { key: 'all', label: '全部记录' }] : [{ key: 'all', label: '全部记录' }] }),
      React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
        pagination: { total: total, page: page, size: SIZE, onChange: setPage }, emptyText: '暂无服务记录' })),
    pointsRow ? React.createElement(GrantPointsModal, { row: pointsRow, onClose: function () { setPointsRow(null); }, onSaved: load }) : null);
}
function GrantPointsModal(props) {
  var r = props.row;
  var [factor, setFactor] = useState(0); // 0正常/1减半/2不发
  var [saving, setSaving] = useState(false);
  function submit() {
    setSaving(true);
    API.post('/a/activity/attendances/' + r.attendanceId + '/points', { pointsFactor: factor })
      .then(function (pts) { window.message.success('已发放积分' + (pts != null ? ' +' + pts : '')); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Modal, { open: true, title: '发放积分 · ' + (r.volunteerName || ''), onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit, disabled: saving }, saving ? '发放中…' : '确认发放')) },
    React.createElement('div', { style: { marginBottom: 16 } }, React.createElement(Descriptions, { items: [
      { label: '活动', value: r.activityTitle || '—' }, { label: '服务时长', value: fmtDur(r.serviceMinutes) } ] })),
    React.createElement(Field, { label: '发放方式', style: { marginBottom: 8 } },
      React.createElement(RadioGroup, { vertical: true, value: factor, onChange: setFactor, options: [
        { value: 0, label: '正常发放（×1）' }, { value: 1, label: '减半发放（×0.5）' }, { value: 2, label: '不发放（×0）' } ] })),
    React.createElement('div', { className: 'cell-sub' }, '最终积分 = 活动积分基数 × 角色倍率（负责人/管理团队/普通）× 系数，由后端计算。'));
}

/* ---------- 11. 考勤变更审核 ---------- */
function AttendanceChangePage(props) {
  var id = props.identity;
  var canAudit = hasPerm(id, 'activity:attendance-audit');
  var [tab, setTab] = useState('0');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var SIZE = 10;

  function load() {
    setLoading(true); setErr(false);
    API.get('/a/activity/attendance-changes', { status: tab === 'all' ? '' : tab, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab, page]);

  function approve(r) {
    window.confirmDialog({ title: '通过 ' + (r.volunteerName || '') + ' 的考勤变更？', content: '通过后将应用「' + changeTypeLabel(r.changeType) + '：' + chgVal(r.changeType, r.oldValue) + ' → ' + chgVal(r.changeType, r.newValue) + '」。', reason: true, reasonRequired: false, reasonLabel: '审核意见（可选）' }).then(function (v) {
      if (v && v.ok) API.post('/a/activity/attendance-changes/' + r.id + '/approve', { reason: v.reason || null }).then(function () { window.message.success('已通过并应用变更'); load(); });
    });
  }
  function reject(r) {
    window.confirmDialog({ title: '拒绝该考勤变更？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝理由' }).then(function (v) {
      if (v && v.ok) API.post('/a/activity/attendance-changes/' + r.id + '/reject', { reason: v.reason }).then(function () { window.message.success('已拒绝'); load(); });
    });
  }

  var body;
  if (loading) body = React.createElement('div', { style: { padding: '32px', textAlign: 'center', color: 'var(--text-3)' } }, '加载中…');
  else if (err) body = React.createElement('div', { style: { padding: '32px', textAlign: 'center' } }, React.createElement(Btn, { onClick: load }, '加载失败，重试'));
  else if (list.length === 0) body = React.createElement(EmptyState, { text: '没有相关变更申请' });
  else body = React.createElement(React.Fragment, null, list.map(function (r) {
    return React.createElement('div', { key: r.id, className: 'card', style: { marginBottom: 12, boxShadow: 'none', border: '1px solid var(--split)' } },
      React.createElement('div', { className: 'card-body' },
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 } },
          React.createElement('span', { className: 'strong', style: { fontSize: 15 } }, r.volunteerName || ('志愿者#' + r.volunteerId)),
          React.createElement(Tag, { color: 'default' }, r.activityTitle || ('活动#' + r.activityId)),
          React.createElement('span', { style: { marginLeft: 'auto' } }, React.createElement(StatusTag, { map: 'audit', value: r.status, dot: true }))),
        React.createElement(Diff, { rows: [{ k: changeTypeLabel(r.changeType), old: chgVal(r.changeType, r.oldValue), 'new': chgVal(r.changeType, r.newValue) }] }),
        React.createElement('div', { style: { display: 'flex', gap: 16, marginTop: 12, fontSize: 13, color: 'var(--text-3)', flexWrap: 'wrap' } },
          r.reason ? React.createElement('span', null, '理由：' + r.reason) : null,
          r.requestedTime ? React.createElement('span', null, String(r.requestedTime).slice(0, 16)) : null,
          (r.status !== 0 && r.auditReason) ? React.createElement('span', null, '审核意见：' + r.auditReason) : null),
        (r.status === 0 && canAudit) ? React.createElement('div', { style: { display: 'flex', gap: 8, marginTop: 14, justifyContent: 'flex-end' } },
          React.createElement(Btn, { danger: true, onClick: function () { reject(r); } }, '拒绝'),
          React.createElement(Btn, { type: 'primary', onClick: function () { approve(r); } }, '通过并应用')) :
          (r.status === 0 ? React.createElement('div', { style: { marginTop: 12, textAlign: 'right' } }, React.createElement('span', { className: 'muted' }, '无审核权限（需 attendance-audit）')) : null)));
  }), React.createElement(Pagination, { total: total, page: page, size: SIZE, onChange: setPage }));

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '考勤变更二次审核', desc: '组织部申请改签到/签退/积分（不立即生效），由部长审核。审核展示原值→新值对照。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: [
        { key: '0', label: '待审' }, { key: '1', label: '已通过' }, { key: '2', label: '已拒绝' }, { key: 'all', label: '全部' } ] }),
      React.createElement('div', { style: { padding: 16 } }, body)));
}

/* ---------- 12. 活动补录审核 ---------- */
function BackfillPage(props) {
  var id = props.identity;
  var canAudit = hasPerm(id, 'activity:backfill-audit');
  var [tab, setTab] = useState('0');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var SIZE = 10;

  function load() {
    setLoading(true); setErr(false);
    API.get('/a/activity/backfills', { status: tab === 'all' ? '' : tab, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab, page]);

  function approve(r) {
    window.confirmDialog({ title: '通过 ' + (r.volunteerName || '') + ' 的补录？', content: '通过后将落入一条已确认考勤：' + fmtDur(r.serviceMinutes) + ' · ' + (r.grantPoints === 1 ? '发放积分' : '仅记时长') + '。' }).then(function (ok) {
      if (ok) API.post('/a/activity/backfills/' + r.id + '/approve').then(function () { window.message.success('已通过补录'); load(); });
    });
  }
  function reject(r) {
    window.confirmDialog({ title: '拒绝该补录申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝理由' }).then(function (v) {
      if (v && v.ok) API.post('/a/activity/backfills/' + r.id + '/reject', { reason: v.reason }).then(function () { window.message.success('已拒绝'); load(); });
    });
  }
  var cols = [
    { title: '志愿者', key: 'vname', width: 150, render: function (r) {
      return React.createElement('div', null, React.createElement('div', { className: 'strong' }, r.volunteerName || ('#' + r.volunteerId)),
        React.createElement('div', { className: 'cell-sub' }, '匹配：' + (r.matchedBy === 'phone' ? '手机号' : r.matchedBy === 'idCard' ? '身份证' : (r.matchedBy || '—'))));
    }},
    { title: '活动 / 时间段', key: 'activity', render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, r.activityTitle || ('活动#' + r.activityId)), React.createElement('div', { className: 'cell-sub' }, '时间段 #' + r.slotId));
    }},
    { title: '拟发时长/积分', key: 'plan', width: 150, align: 'center', render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, fmtDur(r.serviceMinutes)),
        React.createElement('div', { style: { color: r.grantPoints === 1 ? 'var(--success)' : 'var(--text-3)', fontWeight: 600, fontSize: 13 } }, r.grantPoints === 1 ? '发放积分' : '仅记时长'));
    }},
    { title: '理由 / 时间', key: 'reason', width: 180, render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, r.reason || '—'), React.createElement('div', { className: 'cell-sub' }, r.requestedTime ? String(r.requestedTime).slice(0, 16) : ''));
    }},
    { title: '状态', key: 'st', width: 90, render: function (r) { return React.createElement(StatusTag, { map: 'audit', value: r.status, dot: true }); } },
    { title: '操作', key: 'act', width: 140, render: function (r) {
      if (r.status !== 0) return React.createElement('span', { className: 'muted' }, '已处理');
      if (!canAudit) return React.createElement('span', { className: 'muted' }, '无审核权限');
      return React.createElement(AuditActions, { onApprove: function () { approve(r); }, onReject: function () { reject(r); } });
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '活动补录审核', desc: '历史活动补登考勤：组织部按手机号/身份证 + 时间段定位志愿者发起申请，审核通过后落入已确认考勤行（普通活动发积分、历史活动只记时长）。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: [
        { key: '0', label: '待审' }, { key: '1', label: '已通过' }, { key: '2', label: '已拒绝' }, { key: 'all', label: '全部' } ] }),
      React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
        pagination: { total: total, page: page, size: SIZE, onChange: setPage }, emptyText: '暂无补录申请' })));
}

Object.assign(window, { ServicePage: ServicePage, AttendanceChangePage: AttendanceChangePage, BackfillPage: BackfillPage });
