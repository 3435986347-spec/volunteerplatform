/* ============================================================
   活动管理（真实接口 /a/activity/activities）：列表 / 发布 / 编辑 / 删除 / 复制 /
   周期发布(/recurring) / 历史发布(/historical) / 详情含留言下架 / 地图选点(高德，无 key 回退示意)。
   注：模型为「时间段(slot)」制，名额在 slot.needCount；后端无 category/run_status/报名数 于列表 VO。
   日期时间：后端响应统一空格格式 yyyy-MM-dd HH:mm:ss；入参空格与 ISO T 双格式均可（JacksonConfig 宽松
   反序列化），本文件入参统一发 ISO T。多场次发布/编辑经 SlotsEditor（活动整体起止由各场次最早~最晚派生）。
   ============================================================ */

/* 活动状态：0草稿/1已发布/2已结束/3已取消（4待审/5驳回属审核域，常规列表不出现） */
var ACT_STATUS = { 0: ['草稿', 'default'], 1: ['已发布', 'success'], 2: ['已结束', 'default'], 3: ['已取消', 'error'], 4: ['待审核', 'warning'], 5: ['已驳回', 'error'] };
function actStatusTag(s) { var m = ACT_STATUS[s] || ['—', 'default']; return React.createElement(Tag, { color: m[1] }, m[0]); }

/* ---- 日期时间格式互转 ----
   入参统一发 ISO-8601「yyyy-MM-ddTHH:mm:ss」(带 T，datetime-local 原生格式)；后端 JacksonConfig 宽松
   反序列化对空格与 T 双格式均可，响应统一空格格式。读取后端返回值用 slice/replace 容错，space 或 T 均可解析。 */
function padSec(t) { return (t && t.length === 5) ? t + ':00' : t; }
function joinDT(date, time) { return (date && time) ? date + 'T' + padSec(time) : null; }            // date+time 输入 → LDT(ISO)
function fromLocal(s) { if (!s) return null; s = String(s); return s.length === 16 ? s + ':00' : s; }  // datetime-local(本就带 T) → LDT(ISO)
function datePart(s) { return s ? String(s).slice(0, 10) : ''; }
function timePart(s) { return s ? String(s).replace('T', ' ').slice(11, 16) : ''; }
function toLocalInput(s) { return s ? String(s).replace(' ', 'T').slice(0, 16) : ''; }                // LDT(space|T) → datetime-local
function fmtRange(a, b) {
  if (!a) return '—';
  if (!b) return datePart(a) + ' ' + timePart(a);
  // 跨日活动结束侧带日期，避免「06-28 07:00–07:00」这种看不出跨到哪天的显示
  var sameDay = datePart(a) === datePart(b);
  return datePart(a) + ' ' + timePart(a) + '–' + (sameDay ? '' : datePart(b) + ' ') + timePart(b);
}
function numOrNull(v) { return (v === '' || v == null) ? null : Number(v); }
function ymd(d) { var m = d.getMonth() + 1, day = d.getDate(); return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day); } // 本地时区 yyyy-MM-dd（避免 toISOString 跨时区错位）

function ActivitiesPage(props) {
  var id = props.identity;
  var [kw, setKw] = useState(''); var [status, setStatus] = useState('all');
  var [page, setPage] = useState(1);
  var [list, setList] = useState([]); var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(false); var [err, setErr] = useState(false);
  var [editDrawer, setEditDrawer] = useState(null); // {mode:'create'|'edit', id?, historical?}
  var [recurring, setRecurring] = useState(false);
  var [detailId, setDetailId] = useState(null);
  var SIZE = 10;

  var statusOpts = [{ value: 'all', label: '全部状态' }, { value: '0', label: '草稿' }, { value: '1', label: '已发布' }, { value: '2', label: '已结束' }, { value: '3', label: '已取消' }];

  function load() {
    setLoading(true); setErr(false);
    API.get('/a/activity/activities', { keyword: kw, status: status === 'all' ? '' : status, page: page, size: SIZE })
      .then(function (res) { setList(res.records || []); setTotal(Number(res.total) || 0); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [page, status, kw]);

  function delActivity(a) {
    window.confirmDialog({ title: '删除活动「' + a.title + '」？', danger: true, okText: '删除',
      content: '删除后不可恢复，相关报名与考勤数据也将一并清除。' }).then(function (ok) {
      if (ok) API.del('/a/activity/activities/' + a.id).then(function () { window.message.success('已删除活动'); load(); });
    });
  }
  function copyActivity(a) {
    API.post('/a/activity/activities/' + a.id + '/copy').then(function () { window.message.success('已复制活动'); load(); });
  }

  var cols = [
    { title: '活动名称', key: 'title', render: function (r) {
      return React.createElement('div', null,
        React.createElement('div', { className: 'cell-primary' }, r.title),
        React.createElement('div', { className: 'cell-sub' }, (r.serialNo ? '编号 ' + r.serialNo + '　' : ''), r.location || '—'));
    }},
    { title: '时间', key: 'date', width: 180, render: function (r) {
      return React.createElement('div', null, React.createElement('div', null, fmtRange(r.startTime, r.endTime)),
        React.createElement('div', { className: 'cell-sub' }, r.enrollDeadline ? '报名截止 ' + datePart(r.enrollDeadline) : '报名截止 至活动结束'));
    }},
    { title: '报名审核', key: 'audit', width: 96, align: 'center', render: function (r) {
      // 这是「报名是否需审核」(needAudit)，与「活动发布审核」(status 4→1) 是两回事——后者通过后活动状态即「已发布」
      return r.needAudit === 1 ? React.createElement(Tag, { color: 'blue' }, '需审核') : React.createElement('span', { className: 'muted' }, '免审');
    }},
    { title: '状态', key: 'status', width: 100, align: 'center', render: function (r) { return actStatusTag(r.status); } },
    { title: '操作', key: 'act', width: 220, render: function (r) {
      var moreItems = [];
      if (hasPerm(id, 'activity:publish')) moreItems.push({ icon: 'copy', label: '复制活动', onClick: function () { copyActivity(r); } });
      if (hasPerm(id, 'activity:delete')) {
        if (moreItems.length) moreItems.push({ divider: true });
        moreItems.push({ icon: 'trash', label: '删除', danger: true, onClick: function () { delActivity(r); } });
      }
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setDetailId(r.id); } }, '详情'),
        React.createElement(Auth, { code: 'activity:edit' }, React.createElement(React.Fragment, null,
          React.createElement('span', { className: 'act-sep' }),
          React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setEditDrawer({ mode: 'edit', id: r.id }); } }, '修改'))),
        moreItems.length ? React.createElement(React.Fragment, null,
          React.createElement('span', { className: 'act-sep' }),
          React.createElement(Dropdown, { items: moreItems, trigger: React.createElement('button', { className: 'btn-link' }, '更多 ▾') })) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '活动列表 / 发布', desc: '管理协会全部志愿活动。新增/编辑/详情统一用右侧抽屉，不跳页。',
      actions: React.createElement(Auth, { code: 'activity:publish' }, React.createElement(React.Fragment, null,
        React.createElement(Btn, { icon: 'history', onClick: function () { setEditDrawer({ mode: 'create', historical: true }); } }, '历史活动发布'),
        React.createElement(Btn, { icon: 'refresh', onClick: function () { setRecurring(true); } }, '周期发布'),
        React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEditDrawer({ mode: 'create' }); } }, '发布活动'))) }),
    React.createElement(Toolbar, { filters: React.createElement(React.Fragment, null,
      React.createElement(Search, { value: kw, onChange: function (v) { setKw(v); setPage(1); }, placeholder: '搜索活动名称', width: 260 }),
      React.createElement(Select, { inline: true, value: status, onChange: function (v) { setStatus(v); setPage(1); }, options: statusOpts, minWidth: 130 })) }),
    React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, onRowClick: function (r) { setDetailId(r.id); },
      density: props.density, zebra: props.zebra,
      pagination: { total: total, page: page, size: SIZE, onChange: setPage } }),

    editDrawer ? React.createElement(ActivityFormDrawer, { state: editDrawer, onClose: function () { setEditDrawer(null); }, onSaved: load }) : null,
    recurring ? React.createElement(RecurringDrawer, { onClose: function () { setRecurring(false); }, onSaved: load }) : null,
    detailId ? React.createElement(ActivityDetailDrawer, { id: detailId, identity: id, onClose: function () { setDetailId(null); },
      onEdit: function (aid) { setDetailId(null); setEditDrawer({ mode: 'edit', id: aid }); } }) : null);
}

/* ---------- 服务保障 12 项（key/顺序对齐后端 ServiceGuarantee 与小程序 utils/service-guarantees.js） ---------- */
var GUARANTEE_OPTIONS = [
  { key: 'clothing', label: '志愿者服装' }, { key: 'water', label: '提供饮水' },
  { key: 'certificate', label: '志愿服务证书' }, { key: 'training', label: '专项培训' },
  { key: 'insurance', label: '志愿者保险' }, { key: 'traffic', label: '交通补贴' },
  { key: 'meal', label: '餐饮或食物' }, { key: 'bus', label: '集中乘车' },
  { key: 'hotel', label: '提供住宿' }, { key: 'tool', label: '志愿服务工具' },
  { key: 'checkup', label: '免费体检' }, { key: 'other', label: '其他' },
];
var GUARANTEE_LABEL = {}; GUARANTEE_OPTIONS.forEach(function (g) { GUARANTEE_LABEL[g.key] = g.label; });
// 多选 chip 选择器（复用 .radio-btn 切换样式，与周期发布的「重复星期」同款）
function GuaranteePicker(props) {
  var value = props.value || [];
  function toggle(key) {
    var has = value.indexOf(key) >= 0;
    props.onChange(has ? value.filter(function (k) { return k !== key; }) : value.concat([key]));
  }
  return React.createElement('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } },
    GUARANTEE_OPTIONS.map(function (g) {
      return React.createElement('div', { key: g.key, className: 'radio-btn' + (value.indexOf(g.key) >= 0 ? ' on' : ''),
        style: { borderRadius: 6, marginLeft: 0 }, onClick: function () { toggle(g.key); } }, g.label);
    }));
}
// keys → 中文标签串（详情页展示）
function guaranteeLabelsText(keys) {
  if (!keys || !keys.length) return '';
  return keys.map(function (k) { return GUARANTEE_LABEL[k] || k; }).join('、');
}

/* ---------- 活动场次编辑器（可增删多个时间段）----------
   每个场次 {projectName,start,end,needCount}，start/end 为 datetime-local 值（各自带完整日期，
   跨日场次结束日期原样保留——早前「date+start/end 时刻」三字段模型会在编辑保存时把结束压回开始日期）。
   活动整体起止由各场次最早开始~最晚结束派生（见 save()）。
   后端按 slots[] 落库、报名按场次(slotId)，validateDto 要求每段落在活动整体区间内。 */
function SlotsEditor(props) {
  var slots = props.slots || [];
  function upd(i, k, v) {
    props.onChange(slots.map(function (s, idx) { if (idx !== i) return s; var n = Object.assign({}, s); n[k] = v; return n; }));
  }
  function add() {
    // 新场次沿用上一场次的日期（时刻回默认），减少同日多场的重复输入
    var last = slots[slots.length - 1];
    var prevDate = last && last.start ? String(last.start).slice(0, 10) : '';
    props.onChange(slots.concat([{ projectName: '', start: prevDate ? prevDate + 'T08:30' : '', end: prevDate ? prevDate + 'T11:30' : '', needCount: '' }]));
  }
  function remove(i) { props.onChange(slots.filter(function (s, idx) { return idx !== i; })); }
  return React.createElement('div', null,
    slots.map(function (s, i) {
      return React.createElement('div', { key: i, style: { border: '1px solid var(--split)', borderRadius: 8, padding: '12px 14px', marginBottom: 10, background: 'var(--fill-1)' } },
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', marginBottom: 8 } },
          React.createElement('span', { className: 'strong', style: { flex: 1 } }, '场次 ' + (i + 1)),
          slots.length > 1 ? React.createElement('button', { className: 'btn-link danger', onClick: function () { remove(i); } }, '删除') : null),
        React.createElement(Field, { label: '项目名称', hint: '不填默认用活动名称' },
          React.createElement(Input, { value: s.projectName, onChange: function (v) { upd(i, 'projectName', v); }, placeholder: '如：搬运组 / 引导组' })),
        React.createElement('div', { className: 'field-row' },
          React.createElement(Field, { label: '开始', required: true }, React.createElement(Input, { type: 'datetime-local', value: s.start, onChange: function (v) { upd(i, 'start', v); } })),
          React.createElement(Field, { label: '结束', required: true }, React.createElement(Input, { type: 'datetime-local', value: s.end, onChange: function (v) { upd(i, 'end', v); } })),
          React.createElement(Field, { label: '需求人数', hint: '0 不限' }, React.createElement(Input, { type: 'number', value: s.needCount, onChange: function (v) { upd(i, 'needCount', v); } }))));
    }),
    React.createElement('button', { className: 'btn-link', onClick: add }, '+ 添加场次'));
}

/* ---------- 发布/编辑活动抽屉（基础信息 + 封面 + 多场次 + 报名开放 + 地点 + 门槛/联系人 + 服务保障） ---------- */
function blankActivityForm() {
  return { title: '', coverImageUrl: '', place: '',
    slots: [{ projectName: '', start: '', end: '', needCount: '' }],
    pointsBase: '', radius: 200, lng: '', lat: '', contact: '', contactPhone: '', desc: '',
    volOpen: '', mgrOpen: '', enrollDeadline: '', cancelDeadline: '', joinCount: 0, joinMinutes: 0,
    serviceGuarantees: [] };
}
function activityFormFromDetail(d) {
  // start/end 各自 toLocalInput 完整回填（含日期）——跨日场次编辑再保存不丢结束日期
  var slots = (d.slots && d.slots.length ? d.slots : []).map(function (sl) {
    return { projectName: sl.projectName || '', start: toLocalInput(sl.startTime), end: toLocalInput(sl.endTime), needCount: sl.needCount != null ? sl.needCount : '' };
  });
  if (!slots.length) slots = [{ projectName: '', start: toLocalInput(d.startTime), end: toLocalInput(d.endTime), needCount: '' }];
  return {
    title: d.title || '', coverImageUrl: d.coverImageUrl || '', place: d.location || '',
    slots: slots,
    pointsBase: d.pointsBase != null ? d.pointsBase : '',
    radius: d.checkInRadiusM != null ? d.checkInRadiusM : 200,
    lng: d.lng != null ? d.lng : '', lat: d.lat != null ? d.lat : '',
    contact: d.contactName || '', contactPhone: d.contactPhone || '', desc: d.content || '',
    volOpen: toLocalInput(d.enrollOpenVolunteer), mgrOpen: toLocalInput(d.enrollOpenManager),
    enrollDeadline: toLocalInput(d.enrollDeadline), cancelDeadline: toLocalInput(d.cancelDeadline),
    joinCount: d.requireMinJoinCount != null ? d.requireMinJoinCount : 0,
    joinMinutes: d.requireMinJoinMinutes != null ? d.requireMinJoinMinutes : 0,
    serviceGuarantees: d.serviceGuarantees || [],
  };
}
function ActivityFormDrawer(props) {
  var s = props.state, isEdit = s.mode === 'edit';
  var titlePrefix = isEdit ? '修改活动' : s.historical ? '历史活动发布' : '发布活动';
  var [f, setF] = useState(blankActivityForm());
  var [loading, setLoading] = useState(!!s.id);
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }

  useEffect(function () {
    if (!s.id) return;
    setLoading(true);
    API.get('/a/activity/activities/' + s.id).then(function (d) {
      setF(activityFormFromDetail(d));
    }).catch(function () {}).then(function () { setLoading(false); });
  }, []);

  function save() {
    if (!f.title) { window.message.error('请填写活动名称'); return; }
    var rows = f.slots || [];
    if (!rows.length) { window.message.error('请至少添加一个活动场次'); return; }
    var built = [];
    for (var i = 0; i < rows.length; i++) {
      var sl = rows[i];
      if (!sl.start || !sl.end) { window.message.error('场次 ' + (i + 1) + ' 请填写起止时间'); return; }
      var st = fromLocal(sl.start), et = fromLocal(sl.end);
      if (st >= et) { window.message.error('场次 ' + (i + 1) + ' 开始须早于结束'); return; }
      built.push({ projectName: sl.projectName || f.title, startTime: st, endTime: et, needCount: Number(sl.needCount) || 0 });
    }
    // 活动整体起止 = 各场次最早开始 ~ 最晚结束（ISO「yyyy-MM-ddTHH:mm:ss」同格式可直接字典序比较），满足后端「每段须落在整体区间内」
    var startTime = built[0].startTime, endTime = built[0].endTime;
    built.forEach(function (b) { if (b.startTime < startTime) startTime = b.startTime; if (b.endTime > endTime) endTime = b.endTime; });
    var latV = (f.lat == null ? '' : String(f.lat).trim()), lngV = (f.lng == null ? '' : String(f.lng).trim());
    var hasCoord = latV !== '' && lngV !== '';
    var body = {
      title: f.title, coverImageUrl: f.coverImageUrl || null, location: f.place || null, content: f.desc || null,
      startTime: startTime, endTime: endTime,
      enrollDeadline: fromLocal(f.enrollDeadline) || null, // 留空交后端默认=活动结束时间（报名持续到结束）
      pointsBase: numOrNull(f.pointsBase),
      contactName: f.contact || null, contactPhone: f.contactPhone || null,
      enrollOpenVolunteer: fromLocal(f.volOpen), enrollOpenManager: fromLocal(f.mgrOpen),
      requireMinJoinCount: numOrNull(f.joinCount), requireMinJoinMinutes: numOrNull(f.joinMinutes),
      checkInRadiusM: numOrNull(f.radius),
      lat: hasCoord ? latV : null, lng: hasCoord ? lngV : null,
      // 服务保障：所见即所存——编辑时透传当前勾选（含全不选=[] 清空，对齐后端 update []=清空/null=保留）
      serviceGuarantees: f.serviceGuarantees || [],
      slots: built,
    };
    // cancelDeadline 仅编辑时透传原值（回填自 detail），避免无意清空；新建不传，由后端按策略决定
    if (isEdit) body.cancelDeadline = fromLocal(f.cancelDeadline) || null;
    setSaving(true);
    var req = isEdit ? API.put('/a/activity/activities/' + s.id, body)
      : API.post('/a/activity/activities' + (s.historical ? '/historical' : ''), body);
    req.then(function () {
      window.message.success(isEdit ? '已保存修改' : (s.historical ? '历史活动已发布' : '已发布活动'));
      props.onSaved && props.onSaved(); props.onClose();
    }).catch(function () {}).then(function () { setSaving(false); });
  }

  var catalog = React.createElement(React.Fragment, null,
    s.historical ? React.createElement(Alert, { type: 'warning', style: { marginBottom: 20 } }, '历史活动用于补登过往线下服务，发布后不会出现在志愿者端，仅作为补录考勤的载体。') : null,
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '基础信息'),
      React.createElement(Field, { label: '活动名称', required: true },
        React.createElement(Input, { value: f.title, onChange: function (v) { set('title', v); }, placeholder: '如：城西社区敬老助残志愿服务' })),
      React.createElement(Field, { label: '活动封面' }, React.createElement(ImageField, { preset: 'cover', dir: 'activity', previewW: 240, value: f.coverImageUrl, onChange: function (v) { set('coverImageUrl', v); } })),
      React.createElement(Field, { label: '积分基数', hint: '不填用后端默认' }, React.createElement(Input, { type: 'number', value: f.pointsBase, onChange: function (v) { set('pointsBase', v); }, placeholder: '如 60' }))),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '活动场次',
        React.createElement('span', { className: 'sub', style: { marginLeft: 8, fontWeight: 400, fontSize: 12, color: 'var(--text-3)' } }, '可多个；名额按场次设置，活动整体起止自动取最早~最晚')),
      React.createElement(SlotsEditor, { slots: f.slots, onChange: function (v) { set('slots', v); } })),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '报名开放与截止'),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '报名截止时间', hint: '留空默认=活动结束时间（报名持续到活动结束）' }, React.createElement(Input, { type: 'datetime-local', value: f.enrollDeadline, onChange: function (v) { set('enrollDeadline', v); } }))),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '普通志愿者报名开放', hint: '留空=即时可报' }, React.createElement(Input, { type: 'datetime-local', value: f.volOpen, onChange: function (v) { set('volOpen', v); } })),
        React.createElement(Field, { label: '管理团队报名开放', hint: '可早于普通志愿者' }, React.createElement(Input, { type: 'datetime-local', value: f.mgrOpen, onChange: function (v) { set('mgrOpen', v); } })))),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '地点与签到'),
      React.createElement(Field, { label: '活动地点' }, React.createElement(Input, { value: f.place, onChange: function (v) { set('place', v); }, placeholder: '详细地址' })),
      React.createElement(LocationField, { lng: f.lng, lat: f.lat, radius: f.radius,
        onSet: function (lng, lat) { setF(function (p) { return Object.assign({}, p, { lng: lng, lat: lat }); }); },
        onRadius: function (v) { set('radius', v); } })),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '服务保障'),
      React.createElement(Field, { label: '服务保障', hint: '志愿者可享的保障，多选；详情页据此红/灰显示，留空为不提供' },
        React.createElement(GuaranteePicker, { value: f.serviceGuarantees, onChange: function (v) { set('serviceGuarantees', v); } }))),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '资格门槛与联系人'),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '已参加次数门槛', hint: 'requireMinJoinCount · 0 为不限' }, React.createElement(Input, { type: 'number', value: f.joinCount, onChange: function (v) { set('joinCount', v); }, placeholder: '0' })),
        React.createElement(Field, { label: '已参加服务时长门槛', hint: 'requireMinJoinMinutes · 分钟，0 为不限' }, React.createElement(Input, { type: 'number', value: f.joinMinutes, onChange: function (v) { set('joinMinutes', v); }, placeholder: '0' }))),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '现场联系人' }, React.createElement(Input, { value: f.contact, onChange: function (v) { set('contact', v); } })),
        React.createElement(Field, { label: '联系电话' }, React.createElement(Input, { value: f.contactPhone, onChange: function (v) { set('contactPhone', v); } }))),
      React.createElement(Field, { label: '活动说明' }, React.createElement(Textarea, { value: f.desc, onChange: function (v) { set('desc', v); }, placeholder: '活动内容、集合方式、注意事项…', rows: 3 }))));

  return React.createElement(Drawer, { open: true, width: 'wide', title: titlePrefix,
    sub: s.historical ? '历史活动仅作补录载体，志愿者端不可见' : '填写后发布，志愿者端可报名',
    onClose: props.onClose,
    footer: React.createElement(React.Fragment, null,
      React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: loading || saving }, saving ? '提交中…' : (isEdit ? '保存修改' : '确认发布'))) },
    loading ? React.createElement('div', { style: { padding: '40px 0', textAlign: 'center', color: 'var(--text-3)' } }, '加载中…') : catalog);
}

/* ---------- 周期 / 批量发布抽屉（slice 2：真实 POST /a/activity/activities/recurring） ----------
   一份模板按多个目标日批量发布；后端以模板 startTime 的日期为锚点，按天数差整体平移日期、时刻不变。
   目标日 = 显式日期 ∪ (起止范围内落在所选星期的日期)。weekday 1周一…7周日（ISO）。 */
function RecurringDrawer(props) {
  var [mode, setMode] = useState('weekly');
  var [weekdays, setWeekdays] = useState([6]); // ISO：6=周六
  var [from, setFrom] = useState(''); var [to, setTo] = useState('');
  var [datesText, setDatesText] = useState('');
  // 周期发布=单场次模板按多日期平移；这里补回单场次所需的 start/end/quota 默认（blankActivityForm 已改为多场次 slots[]）
  var [f, setF] = useState(Object.assign(blankActivityForm(), { start: '08:30', end: '11:30', quota: 40 }));
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }
  var wdNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']; // index i → ISO i+1
  function toggleWd(iso) { setWeekdays(function (w) { return w.indexOf(iso) >= 0 ? w.filter(function (x) { return x !== iso; }) : w.concat([iso]); }); }

  // 预览：weekly 展开为日期列表（仅展示用，提交仍发 recurStart/End/weekdays）
  var sessions = [];
  if (mode === 'weekly' && from && to && weekdays.length) {
    var d = new Date(from + 'T00:00:00'), end = new Date(to + 'T00:00:00'), guard = 0;
    while (d <= end && guard < 200) {
      var iso = d.getDay() === 0 ? 7 : d.getDay();
      if (weekdays.indexOf(iso) >= 0) sessions.push(ymd(d));
      d.setDate(d.getDate() + 1); guard++;
    }
  }
  var explicitDates = datesText.split(/\s+/).map(function (s) { return s.trim(); }).filter(function (s) { return /^\d{4}-\d{2}-\d{2}$/.test(s); });
  explicitDates = explicitDates.filter(function (s, i) { return explicitDates.indexOf(s) === i; }).sort();
  var count = mode === 'weekly' ? sessions.length : explicitDates.length;

  function submit() {
    if (!f.title) { window.message.error('请填写活动名称'); return; }
    if (!f.start || !f.end) { window.message.error('请填写起止时间'); return; }
    if (mode === 'weekly' && (!from || !to || !weekdays.length)) { window.message.error('请填写起止日期并选择星期'); return; }
    if (mode === 'explicit' && !explicitDates.length) { window.message.error('请填写至少一个有效日期（yyyy-MM-dd）'); return; }
    if (count === 0) { window.message.error('未生成任何场次，请检查日期 / 星期'); return; }
    if (count > 60) { window.message.error('一次最多 60 场，当前 ' + count + ' 场'); return; }
    var anchor = mode === 'weekly' ? from : explicitDates[0];
    var startTime = joinDT(anchor, f.start), endTime = joinDT(anchor, f.end);
    var latV = (f.lat == null ? '' : String(f.lat).trim()), lngV = (f.lng == null ? '' : String(f.lng).trim());
    var hasCoord = latV !== '' && lngV !== '';
    var template = {
      title: f.title, coverImageUrl: f.coverImageUrl || null, location: f.place || null, content: f.desc || null,
      startTime: startTime, endTime: endTime, // enrollDeadline 留空交后端默认=活动结束时间
      pointsBase: numOrNull(f.pointsBase),
      contactName: f.contact || null, contactPhone: f.contactPhone || null,
      checkInRadiusM: numOrNull(f.radius),
      lat: hasCoord ? latV : null, lng: hasCoord ? lngV : null,
      serviceGuarantees: f.serviceGuarantees || [], // 所有场次共用同一组服务保障
      slots: [{ projectName: f.title, startTime: startTime, endTime: endTime, needCount: Number(f.quota) || 0 }],
    };
    var body = { template: template };
    if (mode === 'weekly') { body.recurStart = from; body.recurEnd = to; body.weekdays = weekdays; }
    else { body.dates = explicitDates; }
    setSaving(true);
    API.post('/a/activity/activities/recurring', body).then(function (ids) {
      window.message.success('已周期发布 ' + ((ids && ids.length) || count) + ' 场活动');
      props.onSaved && props.onSaved(); props.onClose();
    }).catch(function () {}).then(function () { setSaving(false); });
  }

  return React.createElement(Drawer, { open: true, width: 'wide', title: '周期 / 批量发布活动',
    sub: '一份模板按多个日期批量发布；每场仅日期平移，时刻不变', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null,
      React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', disabled: saving || count === 0 || count > 60, onClick: submit }, saving ? '发布中…' : ('确认发布 ' + count + ' 场'))) },
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '发布日期'),
      React.createElement(Field, { label: '生成方式' },
        React.createElement(RadioGroup, { button: true, value: mode, onChange: setMode,
          options: [{ value: 'weekly', label: '按星期几 + 起止日期' }, { value: 'explicit', label: '显式多日期' }] })),
      mode === 'weekly' ? React.createElement(React.Fragment, null,
        React.createElement(Field, { label: '重复星期' },
          React.createElement('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap' } }, wdNames.map(function (n, i) {
            var iso = i + 1;
            return React.createElement('div', { key: iso, className: 'radio-btn' + (weekdays.indexOf(iso) >= 0 ? ' on' : ''), style: { borderRadius: 6, marginLeft: 0 }, onClick: function () { toggleWd(iso); } }, n);
          }))),
        React.createElement('div', { className: 'field-row' },
          React.createElement(Field, { label: '起始日期', required: true }, React.createElement(Input, { type: 'date', value: from, onChange: setFrom })),
          React.createElement(Field, { label: '结束日期', required: true }, React.createElement(Input, { type: 'date', value: to, onChange: setTo }))),
        React.createElement(Field, { label: '将生成的场次预览', hint: '共 ' + sessions.length + ' 场' + (sessions.length > 60 ? '（超 60 上限）' : '') },
          React.createElement('div', { style: { display: 'flex', flexWrap: 'wrap', gap: 8, maxHeight: 160, overflow: 'auto', padding: 4 } },
            sessions.length ? sessions.map(function (s, i) { return React.createElement(Tag, { key: i, color: 'blue' }, s); }) : React.createElement('span', { className: 'muted' }, '请选择星期与日期范围')))) :
        React.createElement(Field, { label: '显式日期列表', hint: '每行 / 空格一个日期，如 2026-06-08；已识别 ' + explicitDates.length + ' 个' },
          React.createElement(Textarea, { rows: 5, value: datesText, onChange: setDatesText, placeholder: '2026-06-08\n2026-06-15\n2026-06-22' }))),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '活动模板（所有场次共用，仅日期平移）'),
      React.createElement(Field, { label: '活动名称', required: true }, React.createElement(Input, { value: f.title, onChange: function (v) { set('title', v); }, placeholder: '如：文明交通劝导周末岗' })),
      React.createElement(Field, { label: '活动封面' }, React.createElement(ImageField, { preset: 'cover', dir: 'activity', previewW: 240, value: f.coverImageUrl, onChange: function (v) { set('coverImageUrl', v); } })),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '开始时间', required: true }, React.createElement(Input, { type: 'time', value: f.start, onChange: function (v) { set('start', v); } })),
        React.createElement(Field, { label: '结束时间', required: true }, React.createElement(Input, { type: 'time', value: f.end, onChange: function (v) { set('end', v); } })),
        React.createElement(Field, { label: '每场名额', hint: '0 不限' }, React.createElement(Input, { type: 'number', value: f.quota, onChange: function (v) { set('quota', v); } }))),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '积分基数', hint: '不填用默认' }, React.createElement(Input, { type: 'number', value: f.pointsBase, onChange: function (v) { set('pointsBase', v); } })),
        React.createElement(Field, { label: '现场联系人' }, React.createElement(Input, { value: f.contact, onChange: function (v) { set('contact', v); } })),
        React.createElement(Field, { label: '联系电话' }, React.createElement(Input, { value: f.contactPhone, onChange: function (v) { set('contactPhone', v); } }))),
      React.createElement(Field, { label: '活动地点' }, React.createElement(Input, { value: f.place, onChange: function (v) { set('place', v); }, placeholder: '详细地址' })),
      React.createElement(LocationField, { lng: f.lng, lat: f.lat, radius: f.radius,
        onSet: function (lng, lat) { setF(function (p) { return Object.assign({}, p, { lng: lng, lat: lat }); }); },
        onRadius: function (v) { set('radius', v); } }),
      React.createElement(Field, { label: '服务保障', hint: '多选；所有场次共用' },
        React.createElement(GuaranteePicker, { value: f.serviceGuarantees, onChange: function (v) { set('serviceGuarantees', v); } })),
      React.createElement(Field, { label: '活动说明' }, React.createElement(Textarea, { value: f.desc, onChange: function (v) { set('desc', v); }, rows: 3, placeholder: '活动内容、集合方式、注意事项…' }))));
}

/* ---------- 活动详情抽屉（拉取真实详情 + 活动留言列表/下架） ---------- */
function ActivityDetailDrawer(props) {
  var [d, setD] = useState(null);
  var [loading, setLoading] = useState(true);
  var [err, setErr] = useState(false);
  var [msgs, setMsgs] = useState([]);
  useEffect(function () {
    setLoading(true); setErr(false);
    API.get('/a/activity/activities/' + props.id).then(function (res) { setD(res); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }, []);

  function loadMsgs() {
    API.get('/a/activity/activities/' + props.id + '/messages', { page: 1, size: 100 })
      .then(function (res) { setMsgs(res.records || []); }).catch(function () {});
  }
  useEffect(loadMsgs, []);
  function takeDown(m) {
    window.confirmDialog({ title: '下架该留言？', danger: true, okText: '下架', content: '「' + m.content + '」\n下架后志愿者端不再展示。' }).then(function (ok) {
      if (ok) API.del('/a/activity/messages/' + m.id).then(function () { window.message.success('已下架留言'); loadMsgs(); });
    });
  }

  var body;
  if (loading) body = React.createElement('div', { style: { padding: '40px 0', textAlign: 'center', color: 'var(--text-3)' } }, '加载中…');
  else if (err || !d) body = React.createElement('div', { style: { padding: '40px 0', textAlign: 'center', color: 'var(--text-3)' } }, '加载失败');
  else body = React.createElement(React.Fragment, null,
    React.createElement(Descriptions, { items: [
      { label: '编号', value: d.serialNo || '—' },
      { label: '状态', value: actStatusTag(d.status) },
      { label: '活动时间', value: fmtRange(d.startTime, d.endTime) },
      { label: '报名截止', value: d.enrollDeadline ? datePart(d.enrollDeadline) + ' ' + timePart(d.enrollDeadline) : '至活动结束' },
      { label: '活动地点', value: d.location || '—' },
      { label: '坐标 / 半径', value: (d.lng != null && d.lat != null) ? React.createElement('span', { className: 'mono' }, d.lng + ', ' + d.lat + ' · ' + (d.checkInRadiusM || 500) + 'm') : '未启用 GPS 签到' },
      { label: '积分基数', value: d.pointsBase != null ? d.pointsBase : '—' },
      { label: '报名审核', value: d.needAudit === 1 ? '需审核' : '免审' },
      { label: '参加门槛', value: '次数 ' + (d.requireMinJoinCount || 0) + ' · 时长 ' + (d.requireMinJoinMinutes || 0) + ' 分' },
      { label: '现场联系人', value: (d.contactName || '—') + (d.contactPhone ? '　' + d.contactPhone : '') },
      { label: '发布部门', value: d.publisherDeptName || '—' },
      { label: '服务保障', value: guaranteeLabelsText(d.serviceGuarantees) || '—' },
    ] }),
    React.createElement('div', { className: 'form-section-title', style: { margin: '24px 0 8px' } }, '时间段'),
    (d.slots && d.slots.length) ? d.slots.map(function (sl) {
      return React.createElement('div', { key: sl.id, style: { display: 'flex', gap: 10, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid var(--split)' } },
        React.createElement('span', { className: 'strong', style: { flex: 1 } }, sl.projectName || '默认场次'),
        React.createElement('span', { className: 'cell-sub' }, fmtRange(sl.startTime, sl.endTime)),
        React.createElement(Tag, { color: 'default' }, '需求 ' + (sl.needCount === 0 || sl.needCount == null ? '不限' : sl.needCount)));
    }) : React.createElement('div', { className: 'muted', style: { fontSize: 13 } }, '无时间段'),
    // 现场负责人板块：现场管理(activity:manage) 或 指派权(activity:leader-assign) 任一可见（后端 GET /leaders 同口径放行）；
    // 仅查看权(activity:menu)账号不渲染，避免无权请求 403/「加载失败」。板块内「现场管理」按钮仍单独 gate activity:manage。
    (hasPerm(props.identity, 'activity:manage') || hasPerm(props.identity, 'activity:leader-assign'))
      ? React.createElement(LeaderBoard, { activityId: props.id }) : null,
    d.content ? React.createElement(React.Fragment, null,
      React.createElement('div', { className: 'form-section-title', style: { margin: '24px 0 8px' } }, '活动说明'),
      React.createElement('div', { style: { fontSize: 14, color: 'var(--text-2)', whiteSpace: 'pre-wrap' } }, d.content)) : null,
    React.createElement('div', { className: 'form-section-title', style: { margin: '24px 0 8px' } }, '活动留言',
      React.createElement('span', { className: 'sub', style: { marginLeft: 8, fontWeight: 400, fontSize: 13, color: 'var(--text-3)' } }, msgs.length + ' 条')),
    msgs.length === 0 ? React.createElement('div', { className: 'muted', style: { fontSize: 13, padding: '8px 0' } }, '暂无留言') :
      msgs.map(function (m) {
        return React.createElement('div', { key: m.id, style: { display: 'flex', gap: 10, alignItems: 'flex-start', padding: '12px 0', borderBottom: '1px solid var(--split)' } },
          React.createElement(Avatar, { name: m.volunteerName || '匿名', size: 'sm' }),
          React.createElement('div', { style: { flex: 1, minWidth: 0 } },
            React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
              React.createElement('span', { style: { fontWeight: 500, fontSize: 13 } }, m.volunteerName || '匿名'),
              React.createElement('span', { className: 'cell-sub', style: { marginLeft: 'auto' } }, m.createTime ? String(m.createTime).slice(0, 16) : '')),
            React.createElement('div', { style: { fontSize: 14, color: 'var(--text-2)', marginTop: 2 } }, m.content)),
          React.createElement(Auth, { code: 'activity:manage' },
            React.createElement('button', { className: 'btn-link danger', onClick: function () { takeDown(m); } }, '下架')));
      }));

  return React.createElement(Drawer, { open: true, width: 'wide', title: d ? d.title : '活动详情', sub: d ? (d.location || '') : '',
    onClose: props.onClose, footer: React.createElement(React.Fragment, null,
      React.createElement(Btn, { onClick: props.onClose }, '关闭'),
      React.createElement(Auth, { code: 'activity:edit' }, React.createElement(Btn, { type: 'primary', icon: 'edit', onClick: function () { props.onEdit(props.id); } }, '修改活动'))) },
    body);
}

/* ---------- 现场负责人板块（活动详情内）：指派/取消 + 现场管理（开始/结束/统一签退） ----------
   负责人后台预设：从本活动报名志愿者，或从「管理团队」志愿者中选（均落 leaderType=1，用小程序现场签到/统一签退，不占活动报名人数）。
   指派/取消 gate activity:leader-assign；开始/结束/统一签退 gate activity:manage（后端同口径）。 */
function LeaderBoard(props) {
  var aid = props.activityId;
  var [leaders, setLeaders] = useState([]);
  var [loading, setLoading] = useState(true);
  var [err, setErr] = useState(false);
  var [picker, setPicker] = useState(false);

  function load() {
    setLoading(true); setErr(false);
    API.get('/a/activity/activities/' + aid + '/leaders')
      .then(function (r) { setLeaders(r || []); })
      .catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);

  function remove(r) {
    var who = r.volunteerName || (r.leaderType === 2 ? ('后台账号 #' + r.adminUserId) : ('志愿者 #' + r.volunteerId));
    window.confirmDialog({ title: '取消指派「' + who + '」？', danger: true, okText: '取消指派' }).then(function (ok) {
      if (ok) API.del('/a/activity/activities/' + aid + '/leaders/' + r.id).then(function () { window.message.success('已取消指派'); load(); });
    });
  }
  function runManage(path, label) {
    window.confirmDialog({ title: label + '？', content: '该操作影响现场考勤流程，请确认活动状态无误。' }).then(function (ok) {
      if (!ok) return;
      API.post('/a/activity/activities/' + aid + path).then(function (n) {
        window.message.success(label + '成功' + (typeof n === 'number' ? '（' + n + ' 人）' : ''));
      });
    });
  }

  var titleRow = React.createElement('div', { style: { display: 'flex', alignItems: 'center', margin: '24px 0 4px' } },
    React.createElement('span', { className: 'form-section-title', style: { margin: 0, flex: 1 } }, '现场负责人'),
    React.createElement(Auth, { code: 'activity:leader-assign' },
      React.createElement('button', { className: 'btn-link', onClick: function () { setPicker(true); } }, '+ 指派负责人')));

  var listEl;
  if (loading) listEl = React.createElement('div', { className: 'muted', style: { fontSize: 13, padding: '8px 0' } }, '加载中…');
  else if (err) listEl = React.createElement('div', { className: 'muted', style: { fontSize: 13, padding: '8px 0' } }, '加载失败');
  else if (!leaders.length) listEl = React.createElement('div', { className: 'muted', style: { fontSize: 13, padding: '8px 0' } }, '尚未指派负责人');
  else listEl = leaders.map(function (r) {
    var name = r.volunteerName || (r.leaderType === 2 ? ('后台账号 #' + r.adminUserId) : ('志愿者 #' + r.volunteerId));
    return React.createElement('div', { key: r.id, style: { display: 'flex', gap: 10, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid var(--split)' } },
      React.createElement(Avatar, { name: name, size: 'sm' }),
      React.createElement('div', { style: { flex: 1 } },
        React.createElement('div', { style: { fontWeight: 500 } }, name),
        React.createElement('div', { className: 'cell-sub' }, r.assignedTime ? '指派于 ' + String(r.assignedTime).slice(0, 16) : '')),
      React.createElement(StatusTag, { map: 'leaderType', value: r.leaderType }),
      React.createElement(Auth, { code: 'activity:leader-assign' },
        React.createElement('button', { className: 'btn-link danger', style: { marginLeft: 8 }, onClick: function () { remove(r); } }, '取消')));
  });

  var manageBar = React.createElement(Auth, { code: 'activity:manage' },
    React.createElement('div', { style: { display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap', alignItems: 'center' } },
      React.createElement('span', { className: 'cell-sub', style: { marginRight: 4 } }, '现场管理：'),
      React.createElement(Btn, { size: 'sm', onClick: function () { runManage('/start', '开始活动'); } }, '开始活动'),
      React.createElement(Btn, { size: 'sm', onClick: function () { runManage('/finish', '结束活动'); } }, '结束活动'),
      React.createElement(Btn, { size: 'sm', onClick: function () { runManage('/check-outs', '统一签退'); } }, '统一签退')));

  return React.createElement(React.Fragment, null, titleRow, listEl, manageBar,
    picker ? React.createElement(LeaderPicker, { activityId: aid,
      onClose: function () { setPicker(false); },
      onAssigned: function () { setPicker(false); load(); } }) : null);
}

/* 指派抽屉：两 tab — 从报名志愿者 / 从管理团队志愿者；点「指派」→ POST leaders(leaderType=1, refId=volunteer.id) */
function LeaderPicker(props) {
  var aid = props.activityId;
  var [tab, setTab] = useState('enrolled');     // enrolled | manager
  var [rows, setRows] = useState([]);
  var [loading, setLoading] = useState(false);
  var [kw, setKw] = useState('');
  var [saving, setSaving] = useState(false);
  var [loadErr, setLoadErr] = useState('');

  function load() {
    setLoading(true); setKw(''); setLoadErr('');
    var p = tab === 'enrolled'
      ? API.get('/a/activity/activities/' + aid + '/enrollments', { page: 1, size: 100 })
      : API.get('/a/user/volunteers', { managerFlag: 1, page: 1, size: 100 });
    p.then(function (res) {
      var list = (res && res.records) || [];
      if (tab === 'enrolled') {
        var seen = {}; var out = [];
        list.forEach(function (e) {
          if (e.status === 2) return;                 // 跳过已拒绝
          if (seen[e.volunteerId]) return; seen[e.volunteerId] = 1;   // 一人多场次只列一行
          out.push({ refId: e.volunteerId, name: e.realName, sub: e.school || '' });
        });
        setRows(out);
      } else {
        setRows(list.map(function (v) { return { refId: v.id, name: v.name, sub: [v.school, v.group].filter(Boolean).join(' · ') }; }));
      }
    }).catch(function (e) { setRows([]); setLoadErr((e && e.message) || '加载失败'); }).then(function () { setLoading(false); });
  }
  useEffect(load, [tab]);

  function assign(r) {
    setSaving(true);
    API.post('/a/activity/activities/' + aid + '/leaders', { leaderType: 1, refId: r.refId })
      .then(function () { window.message.success('已指派 ' + (r.name || '')); props.onAssigned(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }

  var shown = kw ? rows.filter(function (r) { return (r.name || '').indexOf(kw) >= 0 || (r.sub || '').indexOf(kw) >= 0; }) : rows;
  var body = React.createElement(React.Fragment, null,
    React.createElement(Tabs, { active: tab, onChange: setTab, items: [
      { key: 'enrolled', label: '从报名志愿者' }, { key: 'manager', label: '从管理团队' } ] }),
    React.createElement('div', { style: { margin: '12px 0' } },
      React.createElement(Input, { value: kw, onChange: setKw, placeholder: '搜索姓名 / 学校' })),
    loading ? React.createElement('div', { className: 'muted', style: { padding: '24px 0', textAlign: 'center' } }, '加载中…')
      : loadErr ? React.createElement('div', { className: 'muted', style: { padding: '24px 0', textAlign: 'center' } },
          '加载失败：' + loadErr,
          React.createElement('div', { className: 'cell-sub', style: { marginTop: 6 } },
            tab === 'enrolled' ? '候选名单需「报名查看」(activity:enroll-view) 权限' : '候选名单需「志愿者列表」(user:list) 权限'))
      : !shown.length ? React.createElement('div', { className: 'muted', style: { padding: '24px 0', textAlign: 'center' } },
          tab === 'enrolled' ? '本活动暂无报名志愿者' : '暂无「管理团队」志愿者（先在「志愿者标记与授权」标记）')
      : shown.map(function (r) {
        return React.createElement('div', { key: r.refId, style: { display: 'flex', gap: 10, alignItems: 'center', padding: '10px 0', borderBottom: '1px solid var(--split)' } },
          React.createElement(Avatar, { name: r.name || '志愿者', size: 'sm' }),
          React.createElement('div', { style: { flex: 1 } },
            React.createElement('div', { style: { fontWeight: 500 } }, r.name || ('#' + r.refId)),
            r.sub ? React.createElement('div', { className: 'cell-sub' }, r.sub) : null),
          React.createElement(Btn, { size: 'sm', type: 'primary', disabled: saving, onClick: function () { assign(r); } }, '指派'));
      }));

  return React.createElement(Drawer, { open: true, title: '指派现场负责人', sub: '从报名志愿者或管理团队中选；不占活动报名人数', onClose: props.onClose,
    footer: React.createElement(Btn, { onClick: props.onClose }, '关闭') }, body);
}

window.ActivitiesPage = ActivitiesPage;

/* ---------- 定位 / 地图选点 ---------- */
/* 雷州市大致经纬度范围，用于演示地图选点投影 */
var LZ_BOUNDS = { lngMin: 110.040, lngMax: 110.160, latMin: 20.850, latMax: 20.970 };
function LocationField(props) {
  var [mapOpen, setMapOpen] = useState(false);
  var [locating, setLocating] = useState(false);
  function locate() {
    if (!navigator.geolocation) { window.message.warning('当前环境不支持定位，请用「地图选点」'); return; }
    setLocating(true); window.message.info('正在获取当前位置…');
    navigator.geolocation.getCurrentPosition(function (pos) {
      setLocating(false);
      props.onSet(pos.coords.longitude.toFixed(6), pos.coords.latitude.toFixed(6));
      window.message.success('已读取当前位置坐标');
    }, function (err) {
      setLocating(false);
      // 失败不再填默认坐标——经纬度手填入口已收掉，默认值会被误存导致 GPS 签到启用在错误地点
      window.message.warning('定位失败（' + (err.message || '权限被拒绝') + '），请改用「地图选点」');
    }, { enableHighAccuracy: true, timeout: 8000 });
  }
  var hasCoord = props.lng && props.lat;
  return React.createElement('div', null,
    React.createElement('div', { style: { display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' } },
      React.createElement(Btn, { type: 'primary', icon: 'target', onClick: locate, disabled: locating }, locating ? '定位中…' : '定位当前位置'),
      React.createElement(Btn, { icon: 'mapPin', onClick: function () { setMapOpen(true); } }, '地图选点'),
      hasCoord ? React.createElement(Btn, { onClick: function () { props.onSet('', ''); } }, '清除定位') : null),
    React.createElement('div', { className: 'field-row' },
      // 坐标只读：由「定位当前位置」或「地图选点」自动获取，避免管理员不知经纬度格式手填出错
      React.createElement(Field, { label: '坐标', hint: '点上方「定位当前位置」或「地图选点」自动获取，无需手填' },
        React.createElement(Input, { value: hasCoord ? (props.lng + ', ' + props.lat) : '', disabled: true, placeholder: '未获取（留空则不启用 GPS 签到）' })),
      React.createElement(Field, { label: '签到半径(米)', hint: '超出范围不可签到' }, React.createElement(Input, { type: 'number', value: props.radius, onChange: props.onRadius }))),
    mapOpen ? React.createElement(MapPickModal, { lng: props.lng, lat: props.lat, radius: props.radius,
      onCancel: function () { setMapOpen(false); },
      onOk: function (lng, lat) { props.onSet(lng, lat); setMapOpen(false); window.message.success('已从地图拾取坐标'); } }) : null);
}

/* 高德地图 JS API 动态加载（需在 HTML 注入 window.__AMAP_KEY__；无 key 则 reject → 回退示意选点）。 */
var __amapPromise = null;
function loadAmap() {
  if (window.AMap) return Promise.resolve(window.AMap);
  if (!window.__AMAP_KEY__) return Promise.reject(new Error('no-key'));
  if (!__amapPromise) {
    __amapPromise = new Promise(function (resolve, reject) {
      // 高德 2.0 安全密钥：serviceHost(代理，生产推荐) 优先，否则 securityJsCode；须在加载 maps 脚本前设置
      if (window.__AMAP_SERVICE_HOST__) window._AMapSecurityConfig = { serviceHost: window.__AMAP_SERVICE_HOST__ };
      else if (window.__AMAP_SECURITY_CODE__) window._AMapSecurityConfig = { securityJsCode: window.__AMAP_SECURITY_CODE__ };
      var sc = document.createElement('script');
      sc.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(window.__AMAP_KEY__);
      sc.onload = function () { window.AMap ? resolve(window.AMap) : reject(new Error('amap-undefined')); };
      sc.onerror = function () { __amapPromise = null; reject(new Error('load-failed')); };
      document.head.appendChild(sc);
    });
  }
  return __amapPromise;
}

function MapPickModal(props) {
  var W = 600, H = 360;
  var b = LZ_BOUNDS, spanLng = b.lngMax - b.lngMin, spanLat = b.latMax - b.latMin;
  function fromXY(x, y) { return [(b.lngMin + x / W * spanLng).toFixed(6), (b.latMax - y / H * spanLat).toFixed(6)]; }
  function toXY(lng, lat) { return [(parseFloat(lng) - b.lngMin) / spanLng * W, (b.latMax - parseFloat(lat)) / spanLat * H]; }
  var defLng = parseFloat(props.lng) || 110.0972, defLat = parseFloat(props.lat) || 20.9213;
  var [coord, setCoord] = useState([props.lng || defLng.toFixed(6), props.lat || defLat.toFixed(6)]);
  var [status, setStatus] = useState('loading'); // loading | ready | fallback
  var mapRef = useRef(null);
  var mapInst = useRef(null);

  useEffect(function () {
    var disposed = false;
    loadAmap().then(function (AMap) {
      if (disposed || !mapRef.current) return;
      var center = [defLng, defLat];
      var map = new AMap.Map(mapRef.current, { zoom: 15, center: center });
      var marker = new AMap.Marker({ position: center, draggable: true });
      var circle = new AMap.Circle({ center: center, radius: Number(props.radius) || 500, strokeColor: '#1677ff', strokeWeight: 1, strokeOpacity: 0.6, fillColor: '#1677ff', fillOpacity: 0.12 });
      map.add(marker); map.add(circle);
      function apply(ll) { marker.setPosition(ll); circle.setCenter(ll); setCoord([ll.getLng().toFixed(6), ll.getLat().toFixed(6)]); }
      map.on('click', function (e) { apply(e.lnglat); });
      marker.on('dragend', function (e) { apply(e.target.getPosition()); });
      mapInst.current = map;
      setStatus('ready');
    }).catch(function () { if (!disposed) setStatus('fallback'); });
    return function () { disposed = true; if (mapInst.current) { try { mapInst.current.destroy(); } catch (e) {} mapInst.current = null; } };
  }, []);

  function onGrid(e) {
    var r = e.currentTarget.getBoundingClientRect();
    var x = Math.max(0, Math.min(W, e.clientX - r.left)), y = Math.max(0, Math.min(H, e.clientY - r.top));
    setCoord(fromXY(x, y));
  }
  var gridPt = toXY(coord[0], coord[1]);

  var inner;
  if (status === 'fallback') {
    inner = React.createElement(React.Fragment, null,
      React.createElement('div', { className: 'crop-ratio-note', style: { marginBottom: 10 } },
        React.createElement(Icon, { name: 'info', size: 13 }), '未配置高德 key（window.__AMAP_KEY__），使用示意选点；可手填经纬度微调'),
      React.createElement('div', { onClick: onGrid, style: { position: 'relative', width: W, height: H, maxWidth: '100%', borderRadius: 8, cursor: 'crosshair', overflow: 'hidden', background: '#eef3ec', border: '1px solid var(--split)' } },
        React.createElement('div', { style: { position: 'absolute', left: '8%', top: '20%', width: '34%', height: '36%', background: '#bfe3f2', borderRadius: '46% 54% 60% 40%' } }),
        React.createElement('div', { style: { position: 'absolute', right: '10%', bottom: '12%', width: '26%', height: '30%', background: '#d6ead0', borderRadius: '50% 50% 44% 56%' } }),
        React.createElement('div', { style: { position: 'absolute', left: 0, right: 0, top: '52%', height: 8, background: '#fff', boxShadow: '0 0 0 1px #e3e3e3' } }),
        React.createElement('div', { style: { position: 'absolute', top: 0, bottom: 0, left: '46%', width: 8, background: '#fff', boxShadow: '0 0 0 1px #e3e3e3' } }),
        React.createElement('div', { style: { position: 'absolute', left: gridPt[0], top: gridPt[1], transform: 'translate(-50%,-100%)', color: 'var(--primary)' } }, React.createElement(Icon, { name: 'mapPin', size: 26, solid: false, sw: 2 }))));
  } else {
    inner = React.createElement('div', { style: { position: 'relative' } },
      React.createElement('div', { ref: mapRef, style: { width: '100%', height: H, borderRadius: 8, overflow: 'hidden', background: '#eef3ec' } }),
      status === 'loading' ? React.createElement('div', { style: { position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-3)', background: 'rgba(255,255,255,.6)' } }, '地图加载中…') : null);
  }

  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'overlay' }),
    React.createElement('div', { className: 'modal wide', style: { width: 640 } },
      React.createElement('div', { className: 'modal-head' },
        React.createElement('h3', null, '地图选点'),
        React.createElement('span', { className: 'drawer-close', style: { marginLeft: 'auto' }, onClick: props.onCancel }, React.createElement(Icon, { name: 'close', size: 18 }))),
      React.createElement('div', { className: 'modal-body' },
        inner,
        React.createElement('div', { style: { display: 'flex', gap: 16, marginTop: 12, fontSize: 13 } },
          React.createElement('span', null, '经度：', React.createElement('b', { className: 'mono' }, coord[0])),
          React.createElement('span', null, '纬度：', React.createElement('b', { className: 'mono' }, coord[1])))),
      React.createElement('div', { className: 'modal-foot' },
        React.createElement(Btn, { onClick: props.onCancel }, '取消'),
        React.createElement(Btn, { type: 'primary', icon: 'check', onClick: function () { props.onOk(coord[0], coord[1]); } }, '确认此点'))));
}
