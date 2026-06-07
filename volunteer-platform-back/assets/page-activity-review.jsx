/* ============================================================
   活动发布审核：小程序「管理团队」志愿者 POST /v/activity/activities
   落「待审核发布」status=4，不直接上线，须后台 activity:publish-audit 审核。
   接口（url 文档 V1 已定义）：
     GET  /a/activity/activities/pending-reviews        列表（status 默认 4，传 5 看已驳回，带提交人姓名）
     GET  /a/activity/activities/{id}/review-detail     完整详情（含驳回原因/审核人/时间）
     POST /a/activity/activities/{id}/publish-approve   通过（活动上线 status→1）
     POST /a/activity/activities/{id}/publish-reject    驳回（status→5，body 可填 reason）
   ============================================================ */
function ActivityReviewPage(props) {
  var id = props.identity;
  var [tab, setTab] = useState('4');
  var [detail, setDetail] = useState(null);
  var canAudit = HD.hasPerm(id, 'activity:publish-audit');
  var data = HD.PENDING_ACTIVITIES.filter(function (r) { return tab === 'all' || String(r.status) === tab; });

  function approve(r) {
    window.confirmDialog({ title: '通过并发布「' + r.title + '」？', content: '通过后活动状态 → 1 已发布，志愿者端可见并开放报名。' }).then(function (ok) { if (ok) window.message.success('已通过，活动已上线（status→1）'); });
  }
  function reject(r) {
    window.confirmDialog({ title: '驳回该活动发布？', danger: true, okText: '确认驳回', reason: true, reasonRequired: true, reasonLabel: '驳回原因', reasonPlaceholder: '将回退给发布者，如：地点信息不完整 / 与现有活动冲突' }).then(function (v) { if (v && v.ok) window.message.success('已驳回（status→5），已通知发布者'); });
  }

  var cols = [
    { title: '活动', key: 'title', render: function (r) {
      return React.createElement('div', null,
        React.createElement('div', { className: 'cell-primary' }, r.title),
        React.createElement('div', { className: 'cell-sub' }, React.createElement(Tag, { color: 'default' }, r.cat), '　' + r.date + ' ' + r.time + ' · ' + r.place));
    }},
    { title: '提交人', key: 'publisher', width: 170, render: function (r) {
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
        React.createElement(Avatar, { name: r.publisher, size: 'sm' }),
        React.createElement('div', null,
          React.createElement('div', { style: { fontWeight: 500 } }, r.publisher, '　', React.createElement(Tag, { color: 'purple' }, r.publisherTeam)),
          React.createElement('div', { className: 'cell-sub' }, r.source + ' · ' + r.submitTime)));
    }},
    { title: '名额', key: 'quota', width: 70, align: 'center', render: function (r) { return r.quota + ' 人'; } },
    { title: '状态', key: 'st', width: 130, render: function (r) {
      return React.createElement('div', null, React.createElement(StatusTag, { map: 'pubReview', value: r.status, dot: true }),
        r.status === 5 && r.rejectReason ? React.createElement('div', { className: 'cell-sub', style: { maxWidth: 150, marginTop: 2 } }, r.rejectReason) : null);
    }},
    { title: '操作', key: 'act', width: 180, render: function (r) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setDetail(r); } }, '查看'),
        (r.status === 4 && canAudit) ? React.createElement(React.Fragment, null,
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link', onClick: function () { approve(r); } }, '通过'),
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link danger', onClick: function () { reject(r); } }, '驳回')) :
          (r.status === 4 ? React.createElement('span', { className: 'muted', style: { marginLeft: 8 } }, '无审核权限') : null));
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '活动发布审核', desc: '小程序「管理团队」志愿者发布的活动落「待审核发布」(status=4)，不直接上线，须审核通过后才在志愿者端可见。通过即上线 (status→1)，驳回则回退 (status→5)。' }),
    React.createElement('div', { className: 'card', flush: true },
      React.createElement(Tabs, { active: tab, onChange: setTab, style: { margin: '0 16px' }, items: [
        { key: '4', label: '待审核', count: HD.PENDING_ACTIVITIES.filter(function (r) { return r.status === 4; }).length },
        { key: '1', label: '已通过/上线' }, { key: '5', label: '已驳回', count: HD.PENDING_ACTIVITIES.filter(function (r) { return r.status === 5; }).length }, { key: 'all', label: '全部' } ] }),
      React.createElement(Table, { columns: cols, data: data, density: props.density, zebra: props.zebra, pagination: false, emptyText: tab === '4' ? '暂无待审活动' : '暂无记录' })),

    detail ? React.createElement(Drawer, { open: true, width: 'wide', title: detail.title, sub: 'GET /a/activity/activities/' + detail.id + '/review-detail', onClose: function () { setDetail(null); },
      footer: (detail.status === 4 && canAudit) ? React.createElement(React.Fragment, null,
        React.createElement(Btn, { danger: true, onClick: function () { var d = detail; setDetail(null); reject(d); } }, '驳回'),
        React.createElement(Btn, { type: 'primary', onClick: function () { var d = detail; setDetail(null); approve(d); } }, '通过并发布')) :
        React.createElement(Btn, { onClick: function () { setDetail(null); } }, '关闭') },
      React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16, padding: '12px 14px', background: 'var(--fill-1)', borderRadius: 8 } },
        React.createElement(Avatar, { name: detail.publisher }),
        React.createElement('div', { style: { flex: 1 } },
          React.createElement('div', { className: 'strong' }, detail.publisher, '　', React.createElement(Tag, { color: 'purple' }, detail.publisherTeam)),
          React.createElement('div', { className: 'cell-sub' }, detail.publisherPhone + ' · ' + detail.source + ' · ' + detail.submitTime)),
        React.createElement(StatusTag, { map: 'pubReview', value: detail.status, dot: true })),
      detail.status === 5 ? React.createElement(Alert, { type: 'warning', title: '已驳回', style: { marginBottom: 16 } },
        React.createElement('div', null, detail.rejectReason,
          React.createElement('div', { className: 'cell-sub', style: { marginTop: 4 } }, '审核人：' + detail.reviewer + ' · ' + detail.reviewTime))) : null,
      React.createElement(Descriptions, { items: [
        { label: '活动类别', value: React.createElement(Tag, { color: 'default' }, detail.cat) },
        { label: '活动时间', value: detail.date + '　' + detail.time },
        { label: '活动地点', value: detail.place },
        { label: '坐标 / 半径', value: React.createElement('span', { className: 'mono' }, detail.lng + ', ' + detail.lat + ' · ' + detail.radius + 'm') },
        { label: '招募名额', value: detail.quota + ' 人' },
        { label: '提交人备注', value: detail.note },
      ] })) : null);
}
window.ActivityReviewPage = ActivityReviewPage;
