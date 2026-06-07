/* ============================================================
   服务记录与积分（秘书部）/ 考勤变更审核 / 活动补录审核
   三处共用「待办列表 + 通过/拒绝（带原因）+ 状态标签」审核范式。
   ============================================================ */

/* ---------- 10. 服务记录与积分 ---------- */
function ServicePage(props) {
  var id = props.identity;
  var [tab, setTab] = useState('pending');
  var [secStatus, setSecStatus] = useState('all');
  var [kw, setKw] = useState('');
  var [pointsRow, setPointsRow] = useState(null);
  var canConfirm = HD.hasPerm(id, 'activity:service-confirm');
  var canGrant = HD.hasPerm(id, 'activity:points-grant');

  var data = HD.SERVICE_RECORDS.filter(function (r) {
    if (kw && r.name.indexOf(kw) < 0 && r.activity.indexOf(kw) < 0) return false;
    if (secStatus !== 'all' && String(r.secretaryStatus) !== secStatus) return false;
    if (tab === 'pending' && r.secretaryStatus !== 0) return false;
    return true;
  });
  var pendingCount = HD.SERVICE_RECORDS.filter(function (r) { return r.secretaryStatus === 0; }).length;

  function confirm(r) { window.confirmDialog({ title: '确认 ' + r.name + ' 的服务时长 ' + r.hours + 'h？', content: '确认后可发放积分。' }).then(function (ok) { if (ok) window.message.success('已确认服务时长'); }); }

  var cols = [
    { title: '志愿者', key: 'name', width: 110, render: function (r) { return React.createElement('span', { className: 'strong' }, r.name); } },
    { title: '活动', key: 'activity' },
    { title: '服务日期', key: 'date', width: 110 },
    { title: '服务时长', key: 'hours', width: 100, align: 'center', render: function (r) { return React.createElement('span', { style: { fontVariantNumeric: 'tabular-nums' } }, r.hours + ' h'); } },
    { title: '确认状态', key: 'sec', width: 100, render: function (r) { return React.createElement(StatusTag, { map: 'secretary', value: r.secretaryStatus, dot: true }); } },
    { title: '积分', key: 'points', width: 90, align: 'center', render: function (r) { return React.createElement('span', { style: { color: r.points > 0 ? 'var(--success)' : 'var(--text-3)', fontWeight: 600 } }, '+' + r.points); } },
    { title: '发放状态', key: 'ps', width: 100, render: function (r) { return React.createElement(StatusTag, { map: 'points', value: r.pointsStatus, dot: true }); } },
    { title: '操作', key: 'act', width: 150, render: function (r) {
      return React.createElement('div', { className: 'row-actions' },
        (canConfirm && r.secretaryStatus === 0) ? React.createElement('button', { className: 'btn-link', onClick: function () { confirm(r); } }, '确认时长') : null,
        (canGrant && r.secretaryStatus === 1 && r.pointsStatus === 0) ? React.createElement('button', { className: 'btn-link', onClick: function () { setPointsRow(r); } }, '发放积分') : null,
        (r.pointsStatus === 1) ? React.createElement('span', { className: 'muted' }, '已完成') : null,
        (!canConfirm && !canGrant) ? React.createElement('span', { className: 'muted' }, '—') : null);
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '服务记录与积分', desc: '秘书部确认已签退志愿者的服务时长，并按规则发放积分（可正常/减半/不发）。' }),
    React.createElement(Toolbar, { filters: React.createElement(React.Fragment, null,
      React.createElement(Search, { value: kw, onChange: setKw, placeholder: '搜索志愿者 / 活动', width: 240 }),
      React.createElement(Select, { inline: true, value: secStatus, onChange: setSecStatus, minWidth: 130,
        options: [{ value: 'all', label: '全部确认状态' }, { value: '0', label: '未确认' }, { value: '1', label: '已确认' }] })) }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        { key: 'pending', label: '待确认', count: pendingCount }, { key: 'all', label: '全部记录', count: HD.SERVICE_RECORDS.length } ] }),
      React.createElement(Table, { columns: cols, data: data, density: props.density, zebra: props.zebra, pagination: false, emptyText: '暂无服务记录' })),
    pointsRow ? React.createElement(GrantPointsModal, { row: pointsRow, onClose: function () { setPointsRow(null); } }) : null);
}
function GrantPointsModal(props) {
  var r = props.row;
  var [mode, setMode] = useState('full');
  var base = Math.round(r.hours * 2);
  var pts = mode === 'full' ? base : mode === 'half' ? Math.round(base / 2) : 0;
  return React.createElement(Modal, { open: true, title: '发放积分 · ' + r.name, onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已发放积分 +' + pts); props.onClose(); } }, '确认发放 +' + pts)) },
    React.createElement('div', { style: { marginBottom: 16 } }, React.createElement(Descriptions, { items: [
      { label: '活动', value: r.activity }, { label: '服务时长', value: r.hours + ' 小时' }, { label: '标准积分', value: '+' + base } ] })),
    React.createElement(Field, { label: '发放方式', style: { marginBottom: 0 } },
      React.createElement(RadioGroup, { vertical: true, value: mode, onChange: setMode, options: [
        { value: 'full', label: '正常发放（+' + base + '）' }, { value: 'half', label: '减半发放（+' + Math.round(base / 2) + '）' }, { value: 'none', label: '不发放（+0）' } ] })));
}

/* ---------- 11. 考勤变更审核 ---------- */
function AttendanceChangePage(props) {
  var id = props.identity;
  var [tab, setTab] = useState('0');
  var canAudit = HD.hasPerm(id, 'activity:attendance-audit');
  var data = HD.ATTENDANCE_CHANGES.filter(function (r) { return tab === 'all' || String(r.status) === tab; });
  function approve(r) { window.confirmDialog({ title: '通过 ' + r.name + ' 的考勤变更？', content: '通过后将应用「' + r.field + '：' + r.oldVal + ' → ' + r.newVal + '」。', reason: true, reasonLabel: '审核意见（可选）', reasonRequired: false }).then(function (v) { if (v) window.message.success('已通过并应用变更'); }); }
  function reject(r) { window.confirmDialog({ title: '拒绝该考勤变更？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true, reasonLabel: '拒绝理由' }).then(function (v) { if (v && v.ok) window.message.success('已拒绝'); }); }
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '考勤变更二次审核', desc: '组织部申请改签到/签退/积分（不立即生效），由部长审核。审核展示原值→新值对照。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        { key: '0', label: '待审', count: HD.ATTENDANCE_CHANGES.filter(function (r) { return r.status === 0; }).length },
        { key: '1', label: '已通过' }, { key: '2', label: '已拒绝' }, { key: 'all', label: '全部' } ] }),
      React.createElement('div', { style: { padding: 16 } },
        data.length === 0 ? React.createElement(EmptyState, { text: '没有相关变更申请' }) :
        data.map(function (r) {
          return React.createElement('div', { key: r.id, className: 'card', style: { marginBottom: 12, boxShadow: 'none', border: '1px solid var(--split)' } },
            React.createElement('div', { className: 'card-body' },
              React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 } },
                React.createElement('span', { className: 'strong', style: { fontSize: 15 } }, r.name),
                React.createElement(Tag, { color: 'default' }, r.activity),
                React.createElement('span', { style: { marginLeft: 'auto' } }, React.createElement(StatusTag, { map: 'audit', value: r.status, dot: true }))),
              React.createElement(Diff, { rows: [
                { k: r.field, old: r.oldVal, 'new': r.newVal },
                { k: '积分', old: '+' + r.oldPoints, 'new': '+' + r.newPoints } ] }),
              React.createElement('div', { style: { display: 'flex', gap: 16, marginTop: 12, fontSize: 13, color: 'var(--text-3)', flexWrap: 'wrap' } },
                React.createElement('span', null, '申请人：' + r.applicant),
                React.createElement('span', null, '理由：' + r.reason),
                React.createElement('span', null, r.time)),
              (r.status === 0 && canAudit) ? React.createElement('div', { style: { display: 'flex', gap: 8, marginTop: 14, justifyContent: 'flex-end' } },
                React.createElement(Btn, { danger: true, onClick: function () { reject(r); } }, '拒绝'),
                React.createElement(Btn, { type: 'primary', onClick: function () { approve(r); } }, '通过并应用')) :
                (r.status === 0 ? React.createElement('div', { style: { marginTop: 12, textAlign: 'right' } }, React.createElement('span', { className: 'muted' }, '无审核权限（需 attendance-audit）')) : null)));
        }))));
}

/* ---------- 12. 活动补录审核 ---------- */
function BackfillPage(props) {
  var id = props.identity;
  var [tab, setTab] = useState('0');
  var canAudit = HD.hasPerm(id, 'activity:backfill-audit');
  var data = HD.BACKFILLS.filter(function (r) { return tab === 'all' || String(r.status) === tab; });
  function approve(r) { window.confirmDialog({ title: '通过 ' + r.vname + ' 的补录？', content: '通过后将落入一条已确认考勤：' + r.planHours + 'h · 积分 +' + r.planPoints + '。' }).then(function (ok) { if (ok) window.message.success('已通过补录'); }); }
  function reject(r) { window.confirmDialog({ title: '拒绝该补录申请？', danger: true, okText: '确认拒绝', reason: true, reasonRequired: true }).then(function (v) { if (v && v.ok) window.message.success('已拒绝'); }); }
  var cols = [
    { title: '定位志愿者', key: 'vname', width: 160, render: function (r) {
      return React.createElement('div', null, React.createElement('div', { className: 'strong' }, r.vname),
        React.createElement('div', { className: 'cell-sub mono' }, r.vphone + ' · 身份证尾号' + r.idTail));
    }},
    { title: '活动 / 时间段', key: 'activity', render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, r.activity), React.createElement('div', { className: 'cell-sub' }, r.period));
    }},
    { title: '拟发时长/积分', key: 'plan', width: 130, align: 'center', render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, r.planHours + ' h'), React.createElement('div', { style: { color: 'var(--success)', fontWeight: 600, fontSize: 13 } }, '+' + r.planPoints + ' 积分'));
    }},
    { title: '申请人', key: 'applicant', width: 140, render: function (r) { return React.createElement('div', null, r.applicant, React.createElement('div', { className: 'cell-sub' }, r.reason)); } },
    { title: '状态', key: 'st', width: 90, render: function (r) { return React.createElement(StatusTag, { map: 'audit', value: r.status, dot: true }); } },
    { title: '操作', key: 'act', width: 140, render: function (r) {
      if (r.status !== 0) return React.createElement('span', { className: 'muted' }, '已处理');
      if (!canAudit) return React.createElement('span', { className: 'muted' }, '无审核权限');
      return React.createElement(AuditActions, { onApprove: function () { approve(r); }, onReject: function () { reject(r); } });
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '活动补录审核', desc: '历史活动补登考勤：按手机号/身份证 + 时间段定位志愿者，审核通过后落入已确认考勤行。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        { key: '0', label: '待审', count: HD.BACKFILLS.filter(function (r) { return r.status === 0; }).length },
        { key: '1', label: '已通过' }, { key: '2', label: '已拒绝' }, { key: 'all', label: '全部' } ] }),
      React.createElement(Table, { columns: cols, data: data, density: props.density, zebra: props.zebra, pagination: false, emptyText: '暂无补录申请' })));
}

Object.assign(window, { ServicePage: ServicePage, AttendanceChangePage: AttendanceChangePage, BackfillPage: BackfillPage });
