/* ============================================================
   活动发布审核（真实接口）：小程序「管理团队」志愿者 POST /v/activity/activities
   落「待审核发布」status=4，不直接上线，须后台 activity:publish-audit 审核。
     GET  /a/activity/activities/pending-reviews?status=  列表（仅 4 待审核 / 5 已驳回，带提交人姓名）
     GET  /a/activity/activities/{id}/review-detail        完整详情（含驳回原因/审核人/时间）
     POST /a/activity/activities/{id}/publish-approve      通过（活动上线 status→1）
     POST /a/activity/activities/{id}/publish-reject       驳回（status→5，body 可填 reason ≤512）
   注：fmtRange / actStatusTag / datePart 复用 page-activities.jsx 的全局函数。
   ============================================================ */
function ActivityReviewPage(props) {
  var id = props.identity;
  var canAudit = HD.hasPerm(id, 'activity:publish-audit');
  var [tab, setTab] = useState('4');          // '4' 待审核 | '5' 已驳回（后端只支持这两个）
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [page, setPage] = useState(1);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [detail, setDetail] = useState(null);   // 当前查看的列表行 VO
  var SIZE = 10;

  function load() {
    if (!canAudit) return;                     // 无审核权限不请求（端点本就 403）
    setLoading(true); setErr(false);
    API.get('/a/activity/activities/pending-reviews', { status: tab, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab, page]);

  function approve(r) {
    window.confirmDialog({ title: '通过并发布「' + (r.title || '该活动') + '」？', content: '通过后活动状态 → 已发布，志愿者端可见并开放报名。' }).then(function (ok) {
      if (ok) API.post('/a/activity/activities/' + r.id + '/publish-approve').then(function () { window.message.success('已通过，活动已上线'); load(); });
    });
  }
  function reject(r) {
    window.confirmDialog({ title: '驳回该活动发布？', danger: true, okText: '确认驳回', reason: true, reasonRequired: true, reasonLabel: '驳回原因', reasonPlaceholder: '将回退给发布者，如：地点信息不完整 / 与现有活动冲突' }).then(function (v) {
      if (v && v.ok) API.post('/a/activity/activities/' + r.id + '/publish-reject', { reason: v.reason }).then(function () { window.message.success('已驳回，已通知发布者'); load(); });
    });
  }

  var cols = [
    { title: '活动', key: 'title', render: function (r) {
      return React.createElement('div', null,
        React.createElement('div', { className: 'cell-primary' }, r.title),
        React.createElement('div', { className: 'cell-sub' }, (r.serialNo ? '编号 ' + r.serialNo + '　' : '') + fmtRange(r.startTime, r.endTime) + (r.location ? ' · ' + r.location : '')));
    }},
    { title: '提交人', key: 'submitter', width: 190, render: function (r) {
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
        React.createElement(Avatar, { name: r.submitterName || '—', size: 'sm' }),
        React.createElement('div', null,
          React.createElement('div', { style: { fontWeight: 500 } }, r.submitterName || '（未知）'),
          React.createElement('div', { className: 'cell-sub' }, r.submitTime ? '提交 ' + String(r.submitTime).slice(0, 16) : '')));
    }},
    { title: '操作', key: 'act', width: 200, render: function (r) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setDetail(r); } }, '查看'),
        (tab === '4' && canAudit) ? React.createElement(React.Fragment, null,
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link', onClick: function () { approve(r); } }, '通过'),
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link danger', onClick: function () { reject(r); } }, '驳回')) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '活动发布审核', desc: '小程序「管理团队」志愿者发布的活动落「待审核发布」，不直接上线，须审核通过后才在志愿者端可见。通过即上线，驳回则回退并记录原因。' }),
    !canAudit ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '你没有「活动发布审核」(activity:publish-audit) 权限，无法查看审核队列。') : null,
    canAudit ? React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: [
        { key: '4', label: '待审核' }, { key: '5', label: '已驳回' } ] }),
      React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
        pagination: { total: total, page: page, size: SIZE, onChange: setPage },
        emptyText: tab === '4' ? '暂无待审活动' : '暂无驳回记录' })) : null,

    detail ? React.createElement(ReviewDetailDrawer, { row: detail, canAudit: canAudit,
      onClose: function () { setDetail(null); },
      onApprove: function (d) { setDetail(null); approve(d); },
      onReject: function (d) { setDetail(null); reject(d); } }) : null);
}

/* ---------- 审核详情抽屉（GET …/{id}/review-detail，完整字段 + 驳回留痕） ---------- */
function ReviewDetailDrawer(props) {
  var row = props.row;
  var [d, setD] = useState(null);
  var [loading, setLoading] = useState(true);
  var [err, setErr] = useState(false);
  useEffect(function () {
    setLoading(true); setErr(false);
    API.get('/a/activity/activities/' + row.id + '/review-detail').then(function (res) { setD(res); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }, []);

  var body;
  if (loading) body = React.createElement('div', { style: { padding: '40px 0', textAlign: 'center', color: 'var(--text-3)' } }, '加载中…');
  else if (err || !d) body = React.createElement('div', { style: { padding: '40px 0', textAlign: 'center', color: 'var(--text-3)' } }, '加载失败');
  else body = React.createElement(React.Fragment, null,
    React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16, padding: '12px 14px', background: 'var(--fill-1)', borderRadius: 8 } },
      React.createElement(Avatar, { name: row.submitterName || '—' }),
      React.createElement('div', { style: { flex: 1 } },
        React.createElement('div', { className: 'strong' }, row.submitterName || '（未知提交人）'),
        React.createElement('div', { className: 'cell-sub' }, row.submitTime ? '提交于 ' + String(row.submitTime).slice(0, 16) : '')),
      actStatusTag(d.status)),
    (d.status === 5 && d.publishRejectReason) ? React.createElement(Alert, { type: 'warning', style: { marginBottom: 16 } },
      React.createElement('div', null, '驳回原因：' + d.publishRejectReason,
        React.createElement('div', { className: 'cell-sub', style: { marginTop: 4 } }, '审核时间：' + (d.publishReviewTime ? String(d.publishReviewTime).slice(0, 16) : '—')))) : null,
    React.createElement(Descriptions, { items: [
      { label: '编号', value: d.serialNo || '—' },
      { label: '活动时间', value: fmtRange(d.startTime, d.endTime) },
      { label: '报名截止', value: d.enrollDeadline ? String(d.enrollDeadline).slice(0, 16) : '不限' },
      { label: '活动地点', value: d.location || '—' },
      { label: '坐标 / 半径', value: (d.lng != null && d.lat != null) ? React.createElement('span', { className: 'mono' }, d.lng + ', ' + d.lat + ' · ' + (d.checkInRadiusM || 500) + 'm') : '未启用 GPS 签到' },
      { label: '积分基数', value: d.pointsBase != null ? d.pointsBase : '—' },
      { label: '报名审核', value: d.needAudit === 1 ? '需审核' : '免审' },
      { label: '现场联系人', value: (d.contactName || '—') + (d.contactPhone ? '　' + d.contactPhone : '') },
      { label: '发布部门', value: d.publisherDeptName || '—' },
    ] }),
    React.createElement('div', { className: 'form-section-title', style: { margin: '24px 0 8px' } }, '时间段'),
    (d.slots && d.slots.length) ? d.slots.map(function (sl) {
      return React.createElement('div', { key: sl.id, style: { display: 'flex', gap: 10, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid var(--split)' } },
        React.createElement('span', { className: 'strong', style: { flex: 1 } }, sl.projectName || '默认场次'),
        React.createElement('span', { className: 'cell-sub' }, fmtRange(sl.startTime, sl.endTime)),
        React.createElement(Tag, { color: 'default' }, '需求 ' + (sl.needCount === 0 || sl.needCount == null ? '不限' : sl.needCount)));
    }) : React.createElement('div', { className: 'muted', style: { fontSize: 13 } }, '无时间段'),
    d.content ? React.createElement(React.Fragment, null,
      React.createElement('div', { className: 'form-section-title', style: { margin: '24px 0 8px' } }, '活动说明'),
      React.createElement('div', { style: { fontSize: 14, color: 'var(--text-2)', whiteSpace: 'pre-wrap' } }, d.content)) : null);

  var footer = (d && d.status === 4 && props.canAudit) ? React.createElement(React.Fragment, null,
    React.createElement(Btn, { danger: true, onClick: function () { props.onReject(d); } }, '驳回'),
    React.createElement(Btn, { type: 'primary', onClick: function () { props.onApprove(d); } }, '通过并发布')) :
    React.createElement(Btn, { onClick: props.onClose }, '关闭');

  return React.createElement(Drawer, { open: true, width: 'wide', title: row.title, sub: row.location || '', onClose: props.onClose, footer: footer }, body);
}

window.ActivityReviewPage = ActivityReviewPage;
