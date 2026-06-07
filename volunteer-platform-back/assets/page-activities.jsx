/* ============================================================
   活动管理（真实接口 /a/activity/activities）：列表 / 发布 / 编辑 / 删除 / 复制 /
   周期发布(/recurring) / 历史发布(/historical) / 详情含留言下架 / 地图选点(高德，无 key 回退示意)。
   注：模型为「时间段(slot)」制，名额在 slot.needCount；后端无 category/run_status/报名数 于列表 VO。
   日期时间入参格式须 yyyy-MM-dd HH:mm:ss（与后端 JacksonConfig 反序列化格式一致）。
   多时间段活动暂禁单 slot 表单编辑（保存会全量替换丢 slot）。
   ============================================================ */

/* 活动状态：0草稿/1已发布/2已结束/3已取消（4待审/5驳回属审核域，常规列表不出现） */
var ACT_STATUS = { 0: ['草稿', 'default'], 1: ['已发布', 'success'], 2: ['已结束', 'default'], 3: ['已取消', 'error'], 4: ['待审核', 'warning'], 5: ['已驳回', 'error'] };
function actStatusTag(s) { var m = ACT_STATUS[s] || ['—', 'default']; return React.createElement(Tag, { color: m[1] }, m[0]); }

/* ---- 日期时间格式互转（后端 LocalDateTime = "yyyy-MM-dd HH:mm:ss"） ---- */
function padSec(t) { return (t && t.length === 5) ? t + ':00' : t; }
function joinDT(date, time) { return (date && time) ? date + ' ' + padSec(time) : null; }          // date+time 输入 → LDT
function fromLocal(s) { if (!s) return null; s = String(s).replace('T', ' '); return s.length === 16 ? s + ':00' : s; } // datetime-local → LDT
function datePart(s) { return s ? String(s).slice(0, 10) : ''; }
function timePart(s) { return s ? String(s).slice(11, 16) : ''; }
function toLocalInput(s) { return s ? String(s).replace(' ', 'T').slice(0, 16) : ''; }                // LDT → datetime-local
function fmtRange(a, b) { if (!a) return '—'; return datePart(a) + ' ' + timePart(a) + (b ? '–' + timePart(b) : ''); }
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
        React.createElement('div', { className: 'cell-sub' }, r.enrollDeadline ? '报名截止 ' + datePart(r.enrollDeadline) : '报名截止不限'));
    }},
    { title: '审核', key: 'audit', width: 90, align: 'center', render: function (r) {
      return r.needAudit === 1 ? React.createElement(Tag, { color: 'blue' }, '需审核') : React.createElement('span', { className: 'muted' }, '免审');
    }},
    { title: '状态', key: 'status', width: 100, align: 'center', render: function (r) { return actStatusTag(r.status); } },
    { title: '操作', key: 'act', width: 220, render: function (r) {
      var moreItems = [];
      if (HD.hasPerm(id, 'activity:publish')) moreItems.push({ icon: 'copy', label: '复制活动', onClick: function () { copyActivity(r); } });
      if (HD.hasPerm(id, 'activity:delete')) {
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
          React.createElement(Dropdown, { items: moreItems, trigger: React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); } }, '更多 ▾') })) : null);
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

/* ---------- 发布/编辑活动抽屉（slice 1：基础信息 + 封面 + 时间 + 地点 + 门槛/联系人） ---------- */
function blankActivityForm() {
  return { title: '', coverImageUrl: '', place: '', date: '', start: '08:30', end: '11:30',
    quota: 40, pointsBase: '', radius: 200, lng: '', lat: '', contact: '', contactPhone: '', desc: '',
    volOpen: '', mgrOpen: '', enrollDeadline: '', cancelDeadline: '', joinCount: 0, joinMinutes: 0 };
}
function activityFormFromDetail(d) {
  var slot = (d.slots && d.slots[0]) || {};
  return {
    title: d.title || '', coverImageUrl: d.coverImageUrl || '', place: d.location || '',
    date: datePart(d.startTime), start: timePart(d.startTime), end: timePart(d.endTime),
    quota: slot.needCount != null ? slot.needCount : 0,
    pointsBase: d.pointsBase != null ? d.pointsBase : '',
    radius: d.checkInRadiusM != null ? d.checkInRadiusM : 200,
    lng: d.lng != null ? d.lng : '', lat: d.lat != null ? d.lat : '',
    contact: d.contactName || '', contactPhone: d.contactPhone || '', desc: d.content || '',
    volOpen: toLocalInput(d.enrollOpenVolunteer), mgrOpen: toLocalInput(d.enrollOpenManager),
    enrollDeadline: toLocalInput(d.enrollDeadline), cancelDeadline: toLocalInput(d.cancelDeadline),
    joinCount: d.requireMinJoinCount != null ? d.requireMinJoinCount : 0,
    joinMinutes: d.requireMinJoinMinutes != null ? d.requireMinJoinMinutes : 0,
  };
}
function ActivityFormDrawer(props) {
  var s = props.state, isEdit = s.mode === 'edit';
  var titlePrefix = isEdit ? '修改活动' : s.historical ? '历史活动发布' : '发布活动';
  var [f, setF] = useState(blankActivityForm());
  var [loading, setLoading] = useState(!!s.id);
  var [saving, setSaving] = useState(false);
  var [slotCount, setSlotCount] = useState(1);
  var multiSlot = isEdit && slotCount > 1; // 多时间段活动：单 slot 表单保存会全量替换丢 slot，slice 1 暂禁编辑
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }

  useEffect(function () {
    if (!s.id) return;
    setLoading(true);
    API.get('/a/activity/activities/' + s.id).then(function (d) {
      setF(activityFormFromDetail(d));
      setSlotCount((d.slots && d.slots.length) || 1);
    }).catch(function () {}).then(function () { setLoading(false); });
  }, []);

  function save() {
    if (multiSlot) { window.message.warning('多时间段活动请在后续多时间段编辑功能中处理'); return; }
    if (!f.title) { window.message.error('请填写活动名称'); return; }
    if (!f.date || !f.start || !f.end) { window.message.error('请填写活动日期与起止时间'); return; }
    var startTime = joinDT(f.date, f.start), endTime = joinDT(f.date, f.end);
    var latV = (f.lat == null ? '' : String(f.lat).trim()), lngV = (f.lng == null ? '' : String(f.lng).trim());
    var hasCoord = latV !== '' && lngV !== '';
    var body = {
      title: f.title, coverImageUrl: f.coverImageUrl || null, location: f.place || null, content: f.desc || null,
      startTime: startTime, endTime: endTime,
      enrollDeadline: fromLocal(f.enrollDeadline) || startTime, // 不显式传会被后端默认成 start-24h（近期活动一发布即截止）
      pointsBase: numOrNull(f.pointsBase),
      contactName: f.contact || null, contactPhone: f.contactPhone || null,
      enrollOpenVolunteer: fromLocal(f.volOpen), enrollOpenManager: fromLocal(f.mgrOpen),
      requireMinJoinCount: numOrNull(f.joinCount), requireMinJoinMinutes: numOrNull(f.joinMinutes),
      checkInRadiusM: numOrNull(f.radius),
      lat: hasCoord ? latV : null, lng: hasCoord ? lngV : null,
      slots: [{ projectName: f.title, startTime: startTime, endTime: endTime, needCount: Number(f.quota) || 0 }],
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
    multiSlot ? React.createElement(Alert, { type: 'warning', style: { marginBottom: 20 } }, '该活动含多个时间段（' + slotCount + ' 个）。当前单时间段表单保存会全量替换、丢失其余时间段，已禁用保存；请等多时间段编辑功能上线后再修改。') : null,
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '基础信息'),
      React.createElement(Field, { label: '活动名称', required: true },
        React.createElement(Input, { value: f.title, onChange: function (v) { set('title', v); }, placeholder: '如：城西社区敬老助残志愿服务' })),
      React.createElement(Field, { label: '活动封面' }, React.createElement(ImageField, { preset: 'cover', dir: 'activity', previewW: 240, value: f.coverImageUrl, onChange: function (v) { set('coverImageUrl', v); } })),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '招募名额', hint: '0 为不限' }, React.createElement(Input, { type: 'number', value: f.quota, onChange: function (v) { set('quota', v); } })),
        React.createElement(Field, { label: '积分基数', hint: '不填用后端默认' }, React.createElement(Input, { type: 'number', value: f.pointsBase, onChange: function (v) { set('pointsBase', v); }, placeholder: '如 60' })))),
    React.createElement('div', { className: 'form-section' },
      React.createElement('div', { className: 'form-section-title' }, '时间与报名开放'),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '活动日期', required: true }, React.createElement(Input, { type: 'date', value: f.date, onChange: function (v) { set('date', v); } })),
        React.createElement(Field, { label: '开始时间', required: true }, React.createElement(Input, { type: 'time', value: f.start, onChange: function (v) { set('start', v); } })),
        React.createElement(Field, { label: '结束时间', required: true }, React.createElement(Input, { type: 'time', value: f.end, onChange: function (v) { set('end', v); } }))),
      React.createElement('div', { className: 'field-row' },
        React.createElement(Field, { label: '报名截止时间', hint: '留空默认=活动开始时间（避免一发布即截止）' }, React.createElement(Input, { type: 'datetime-local', value: f.enrollDeadline, onChange: function (v) { set('enrollDeadline', v); } }))),
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
      React.createElement(Btn, { type: 'primary', onClick: save, disabled: loading || saving || multiSlot }, saving ? '提交中…' : (isEdit ? '保存修改' : '确认发布'))) },
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
  var [f, setF] = useState(blankActivityForm());
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
      startTime: startTime, endTime: endTime, enrollDeadline: startTime,
      pointsBase: numOrNull(f.pointsBase),
      contactName: f.contact || null, contactPhone: f.contactPhone || null,
      checkInRadiusM: numOrNull(f.radius),
      lat: hasCoord ? latV : null, lng: hasCoord ? lngV : null,
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
      { label: '报名截止', value: d.enrollDeadline ? datePart(d.enrollDeadline) + ' ' + timePart(d.enrollDeadline) : '不限' },
      { label: '活动地点', value: d.location || '—' },
      { label: '坐标 / 半径', value: (d.lng != null && d.lat != null) ? React.createElement('span', { className: 'mono' }, d.lng + ', ' + d.lat + ' · ' + (d.checkInRadiusM || 500) + 'm') : '未启用 GPS 签到' },
      { label: '积分基数', value: d.pointsBase != null ? d.pointsBase : '—' },
      { label: '报名审核', value: d.needAudit === 1 ? '需审核' : '免审' },
      { label: '参加门槛', value: '次数 ' + (d.requireMinJoinCount || 0) + ' · 时长 ' + (d.requireMinJoinMinutes || 0) + ' 分' },
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
      props.onSet('110.097200', '20.921300');
      window.message.warning('定位失败（' + (err.message || '权限被拒绝') + '），已填入默认坐标，请用「地图选点」微调');
    }, { enableHighAccuracy: true, timeout: 8000 });
  }
  var hasCoord = props.lng && props.lat;
  return React.createElement('div', null,
    React.createElement('div', { style: { display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' } },
      React.createElement(Btn, { type: 'primary', icon: 'target', onClick: locate, disabled: locating }, locating ? '定位中…' : '定位当前位置'),
      React.createElement(Btn, { icon: 'mapPin', onClick: function () { setMapOpen(true); } }, '地图选点'),
      hasCoord ? React.createElement('span', { className: 'crop-ratio-note', style: { alignSelf: 'center' } },
        React.createElement(Icon, { name: 'mapPin', size: 13 }), '已定位 ' + props.lng + ', ' + props.lat) : null),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '经度', hint: '可手动微调' }, React.createElement(Input, { value: props.lng, onChange: function (v) { props.onSet(v, props.lat); }, placeholder: '110.097200' })),
      React.createElement(Field, { label: '纬度' }, React.createElement(Input, { value: props.lat, onChange: function (v) { props.onSet(props.lng, v); }, placeholder: '20.921300' })),
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
