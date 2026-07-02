/* ============================================================
   报名管理团队审核（真实接口）：小程序志愿者 POST /v/organization/manager-applications 提交申请，
   后台按 org:manager-flag 审核；通过即置 volunteer.manager_flag=1（不自动授权限点，权限仍在「子账号/权限」单独给）。
     GET  /a/organization/manager-applications?status=        列表（0待审/1已通过/2已驳回，带申请人姓名）
     POST /a/organization/manager-applications/{id}/approve   通过（标记为管理团队）
     POST /a/organization/manager-applications/{id}/reject    驳回（body 可填 reason ≤512）
   ============================================================ */
function managerApplyStatusTag(status) {
  if (status === 1) return React.createElement(Tag, { color: 'green' }, '已通过');
  if (status === 2) return React.createElement(Tag, { color: 'red' }, '已驳回');
  return React.createElement(Tag, { color: 'blue' }, '待审核');
}

function ManagerApplicationsPage(props) {
  var id = props.identity;
  var canAudit = hasPerm(id, 'org:manager-flag');
  var [tab, setTab] = useState('0');           // 0待审 | 1已通过 | 2已驳回
  var [list, setList] = useState([]);
  var [total, setTotal] = useState(0);
  var [page, setPage] = useState(1);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [detail, setDetail] = useState(null);
  var SIZE = 10;

  function load() {
    if (!canAudit) return;                       // 无权限不请求（端点本就 403）
    setLoading(true); setErr(false);
    API.get('/a/organization/manager-applications', { status: tab, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab, page]);

  function approve(r) {
    window.confirmDialog({ title: '通过「' + (r.volunteerName || '该志愿者') + '」的报名管理团队申请？', content: '通过后该志愿者被标记为管理团队（积分 ×1.2、可被授权）。具体功能权限仍需在「子账号/权限」处单独授予。' }).then(function (ok) {
      if (ok) API.post('/a/organization/manager-applications/' + r.id + '/approve').then(function () { window.message.success('已通过，已标记为管理团队'); load(); });
    });
  }
  function reject(r) {
    window.confirmDialog({ title: '驳回该申请？', danger: true, okText: '确认驳回', reason: true, reasonLabel: '驳回原因', reasonPlaceholder: '将回退给申请人，如：暂不符合管理团队要求' }).then(function (v) {
      if (v && v.ok) API.post('/a/organization/manager-applications/' + r.id + '/reject', { reason: v.reason }).then(function () { window.message.success('已驳回'); load(); });
    });
  }

  var cols = [
    { title: '申请人', key: 'who', width: 200, render: function (r) {
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
        React.createElement(Avatar, { name: r.volunteerName || '—', size: 'sm' }),
        React.createElement('div', null,
          React.createElement('div', { style: { fontWeight: 500 } }, r.volunteerName || '（未知）'),
          React.createElement('div', { className: 'cell-sub' }, r.applyTime ? '申请 ' + String(r.applyTime).slice(0, 16) : '')));
    }},
    { title: '申请理由', key: 'reason', render: function (r) {
      return React.createElement('div', null,
        React.createElement('div', { className: 'cell-primary', style: { whiteSpace: 'normal' } }, r.reason || '—'),
        r.expectDepartment ? React.createElement('div', { className: 'cell-sub' }, '期望部门：' + r.expectDepartment) : null);
    }},
    { title: '状态', key: 'status', width: 90, align: 'center', render: function (r) { return managerApplyStatusTag(r.status); } },
    { title: '操作', key: 'act', width: 200, render: function (r) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setDetail(r); } }, '查看'),
        (tab === '0' && canAudit) ? React.createElement(React.Fragment, null,
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link', onClick: function () { approve(r); } }, '通过'),
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link danger', onClick: function () { reject(r); } }, '驳回')) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '报名管理团队', desc: '志愿者在小程序提交「报名管理团队」申请，审核通过即标记为管理团队（积分 ×1.2、可被授权）。具体功能权限仍需在「子账号/权限」处单独授予。' }),
    !canAudit ? React.createElement(Alert, { type: 'info', style: { marginBottom: 16 } }, '你没有「管理团队标记」(org:manager-flag) 权限，无法审核报名申请。') : null,
    canAudit ? React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: function (t) { setTab(t); setPage(1); }, style: { margin: '0 16px' }, items: [
        { key: '0', label: '待审核' }, { key: '1', label: '已通过' }, { key: '2', label: '已驳回' } ] }),
      React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra,
        pagination: { total: total, page: page, size: SIZE, onChange: setPage },
        emptyText: tab === '0' ? '暂无待审申请' : '暂无记录' })) : null,

    detail ? React.createElement(ManagerApplyDetailDrawer, { row: detail, canAudit: canAudit,
      onClose: function () { setDetail(null); },
      onApprove: function (d) { setDetail(null); approve(d); },
      onReject: function (d) { setDetail(null); reject(d); } }) : null);
}

/* ---------- 申请详情抽屉（直接用列表行数据，无额外接口） ---------- */
function ManagerApplyDetailDrawer(props) {
  var d = props.row;
  var body = React.createElement(React.Fragment, null,
    React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16, padding: '12px 14px', background: 'var(--fill-1)', borderRadius: 8 } },
      React.createElement(Avatar, { name: d.volunteerName || '—' }),
      React.createElement('div', { style: { flex: 1 } },
        React.createElement('div', { className: 'strong' }, d.volunteerName || '（未知）'),
        React.createElement('div', { className: 'cell-sub' }, d.applyTime ? '申请于 ' + String(d.applyTime).slice(0, 16) : '')),
      managerApplyStatusTag(d.status)),
    (d.status === 2 && d.rejectReason) ? React.createElement(Alert, { type: 'warning', style: { marginBottom: 16 } }, '驳回原因：' + d.rejectReason) : null,
    React.createElement(Descriptions, { items: [
      { label: '期望部门', value: d.expectDepartment || '—' },
    ] }),
    React.createElement('div', { className: 'form-section-title', style: { margin: '20px 0 8px' } }, '申请理由 / 自我介绍'),
    React.createElement('div', { style: { fontSize: 14, color: 'var(--text-2)', whiteSpace: 'pre-wrap' } }, d.reason || '—'),
    d.experience ? React.createElement(React.Fragment, null,
      React.createElement('div', { className: 'form-section-title', style: { margin: '20px 0 8px' } }, '相关经历'),
      React.createElement('div', { style: { fontSize: 14, color: 'var(--text-2)', whiteSpace: 'pre-wrap' } }, d.experience)) : null);

  var footer = (d.status === 0 && props.canAudit) ? React.createElement(React.Fragment, null,
    React.createElement(Btn, { danger: true, onClick: function () { props.onReject(d); } }, '驳回'),
    React.createElement(Btn, { type: 'primary', onClick: function () { props.onApprove(d); } }, '通过')) :
    React.createElement(Btn, { onClick: props.onClose }, '关闭');

  return React.createElement(Drawer, { open: true, width: 'wide', title: (d.volunteerName || '申请') + ' · 报名管理团队', onClose: props.onClose, footer: footer }, body);
}

window.ManagerApplicationsPage = ManagerApplicationsPage;
