/* ============================================================
   志愿者管理（GET /a/user/volunteers ...）—— 真实接口
   列表(多筛选/真分页)/详情/修改(全量PUT,仅超管)/停用·恢复(PATCH status)/删除/导出
   鉴权：列表+详情 user:list、导出 user:export、状态 user:status、删除 user:delete；
        修改实名敏感资料 user:edit 写死仅超管。「重置密码」按决策隐藏（微信登录无密码列）。
   状态约定：后端 0正常/1禁用/2注销（与 MAPS.acct 已对齐，勿再反转）。
   ============================================================ */

// 与后端枚举 code 对齐的下拉项（前端展示用）
var VOL_GENDER_OPTS = [{ value: '0', label: '未知' }, { value: '1', label: '男' }, { value: '2', label: '女' }];
var VOL_POLITICAL_OPTS = [
  { value: '1', label: '群众' }, { value: '2', label: '共青团员' }, { value: '3', label: '中共预备党员' },
  { value: '4', label: '中共党员' }, { value: '5', label: '民主党派' }];
var VOL_GRADE_OPTS = [
  { value: '1', label: '一年级' }, { value: '2', label: '二年级' }, { value: '3', label: '三年级' },
  { value: '4', label: '四年级' }, { value: '5', label: '五年级' }, { value: '6', label: '六年级' },
  { value: '7', label: '七年级' }, { value: '8', label: '八年级' }, { value: '9', label: '九年级' },
  { value: '10', label: '高一' }, { value: '11', label: '高二' }, { value: '12', label: '高三' },
  { value: '13', label: '大一' }, { value: '14', label: '大二' }, { value: '15', label: '大三' },
  { value: '16', label: '大四' }, { value: '17', label: '大五' }, { value: '18', label: '毕业' }];

function VolunteersPage(props) {
  var id = props.identity;
  var canEdit = !!id.isSuperAdmin;                 // PUT 仅超管（user:edit 不入权限表）
  var canStatus = hasPerm(id, 'user:status');
  var canDelete = hasPerm(id, 'user:delete');
  var canExport = hasPerm(id, 'user:export');

  var [kw, setKw] = useState('');
  var [gender, setGender] = useState('all');
  var [squad, setSquad] = useState('all');
  var [political, setPolitical] = useState('all');
  var [page, setPage] = useState(1);
  var [rows, setRows] = useState([]);
  var [total, setTotal] = useState(0);
  var [loading, setLoading] = useState(true);
  var [err, setErr] = useState(false);
  var [squadOpts, setSquadOpts] = useState([{ value: 'all', label: '全部分队' }]);
  var [detail, setDetail] = useState(null);
  var [edit, setEdit] = useState(null);
  var pageSize = 10;

  // 分队下拉：best-effort（需 org:squad-manage；无权则只留「全部分队」，不影响其它筛选）
  useEffect(function () {
    API.get('/a/organization/squads', { page: 1, size: 100 }).then(function (r) {
      var list = (r && r.records) || (Array.isArray(r) ? r : []);
      setSquadOpts([{ value: 'all', label: '全部分队' }].concat(list.map(function (s) {
        return { value: String(s.id), label: s.name };
      })));
    }).catch(function () { /* 无分队权限：保持仅「全部分队」 */ });
  }, []);

  function filterQuery() {
    var q = {};
    if (kw.trim()) q.keyword = kw.trim();
    if (gender !== 'all') q.gender = gender;
    if (political !== 'all') q.political = political;
    if (squad !== 'all') q.squad = squad;
    return q;
  }
  function load() {
    setLoading(true); setErr(false);
    var q = filterQuery(); q.page = page; q.size = pageSize;
    API.get('/a/user/volunteers', q).then(function (r) {
      setRows((r && r.records) || []);
      setTotal(Number((r && r.total) || 0));
    }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, [page, kw, gender, squad, political]);
  function resetTo1(setter) { return function (v) { setter(v); setPage(1); }; }

  function toggleStatus(v) {
    var to = v.status === 0 ? 1 : 0;     // 0正常→1禁用；1禁用/2注销→0正常
    window.confirmDialog({ title: (to === 1 ? '禁用' : '启用') + ' ' + v.name + ' 的账号？', danger: to === 1,
      content: to === 1 ? '禁用后该志愿者无法登录小程序、不能报名活动。' : '启用后该志愿者可正常使用。' }).then(function (ok) {
      if (!ok) return;
      API.patch('/a/user/volunteers/' + v.id + '/status', { status: to }).then(function () {
        window.message.success(to === 1 ? '已禁用' : '已启用'); load();
      }).catch(function () {});
    });
  }
  function del(v) {
    window.confirmDialog({ title: '删除志愿者「' + v.name + '」？', danger: true, okText: '删除',
      content: '删除后该志愿者将从列表移除（逻辑删除，服务记录保留）。此操作不可逆。' }).then(function (ok) {
      if (!ok) return;
      API.del('/a/user/volunteers/' + v.id).then(function () {
        window.message.success('已删除志愿者'); load();
      }).catch(function () {});
    });
  }
  function doExport() {
    var q = filterQuery();
    window.message.success('正在导出志愿者 Excel…');
    API.download('/a/user/volunteers/export', '志愿者名单.xlsx', q).catch(function () {});
  }

  function moreItems(v) {
    var items = [];
    // 仅 0正常/1禁用 间互转；注销(2) 视为终态，不提供前端「反注销」入口（只留详情/删除）
    if (canStatus && (v.status === 0 || v.status === 1)) {
      items.push({ icon: v.status === 0 ? 'lock' : 'checkCircle', label: v.status === 0 ? '禁用账号' : '启用账号', onClick: function () { toggleStatus(v); } });
    }
    if (canDelete) { if (items.length) items.push({ divider: true }); items.push({ icon: 'trash', label: '删除', danger: true, onClick: function () { del(v); } }); }
    return items;
  }

  var cols = [
    { title: '志愿者', key: 'name', render: function (v) {
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
        React.createElement(Avatar, { name: v.name || '?' }),
        React.createElement('div', null,
          React.createElement('div', { style: { fontWeight: 500, display: 'flex', alignItems: 'center', gap: 6 } }, v.name,
            v.gender ? React.createElement('span', { className: 'muted', style: { fontWeight: 400, fontSize: 12 } }, v.gender) : null,
            v.managerFlag ? React.createElement(Tag, { color: 'purple' }, '管理团队') : null),
          React.createElement('div', { className: 'cell-sub mono' }, v.phone || '—')));
    }},
    { title: '学校 / 年级', key: 'school', width: 170, render: function (v) {
      return React.createElement('div', null, React.createElement('div', null, v.school || '—'),
        React.createElement('div', { className: 'cell-sub' }, (v.grade || '—') + ' · ' + (v.political || '—')));
    }},
    { title: '分队 / 小组', key: 'squad', width: 160, render: function (v) {
      return React.createElement('div', null,
        v.squad ? React.createElement(Tag, { color: 'cyan' }, v.squad) : React.createElement('span', { className: 'muted' }, '未归属'),
        React.createElement('div', { className: 'cell-sub' }, v.group || '无小组'));
    }},
    { title: '服务时长', key: 'hours', width: 100, align: 'center', render: function (v) {
      return React.createElement('div', null, React.createElement('div', { style: { fontVariantNumeric: 'tabular-nums' } }, (v.hours != null ? v.hours : 0) + ' h'),
        React.createElement('div', { className: 'cell-sub' }, (v.points != null ? v.points : 0) + ' 积分'));
    }},
    { title: '状态', key: 'status', width: 90, render: function (v) { return React.createElement(StatusTag, { map: 'acct', value: v.status, dot: true }); } },
    { title: '操作', key: 'act', width: 200, render: function (v) {
      var items = moreItems(v);
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setDetail(v); } }, '详情'),
        canEdit ? React.createElement('span', { className: 'act-sep' }) : null,
        canEdit ? React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setEdit(v); } }, '修改') : null,
        items.length ? React.createElement('span', { className: 'act-sep' }) : null,
        items.length ? React.createElement(Dropdown, { items: items,
          trigger: React.createElement('button', { className: 'btn-link' }, '更多 ▾') }) : null);
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '志愿者管理', desc: '志愿者列表、详情、修改、停用/恢复、删除、导出（支持按姓名/学校/手机号、性别、分队、政治面貌筛选）。',
      actions: canExport ? React.createElement(Btn, { icon: 'download', onClick: doExport }, '导出志愿者') : null }),
    React.createElement(Toolbar, { filters: React.createElement(React.Fragment, null,
      React.createElement(Search, { value: kw, onChange: resetTo1(setKw), placeholder: '搜索姓名 / 学校 / 手机号', width: 240 }),
      React.createElement(Select, { inline: true, minWidth: 110, value: gender, onChange: resetTo1(setGender), options: [{ value: 'all', label: '全部性别' }, { value: '1', label: '男' }, { value: '2', label: '女' }] }),
      React.createElement(Select, { inline: true, minWidth: 140, value: squad, onChange: resetTo1(setSquad), options: squadOpts }),
      React.createElement(Select, { inline: true, minWidth: 150, value: political, onChange: resetTo1(setPolitical), options: [{ value: 'all', label: '全部政治面貌' }].concat(VOL_POLITICAL_OPTS) })) }),
    React.createElement('div', { style: { display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' } },
      React.createElement('div', { className: 'card', style: { flex: '1 1 160px', padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 12 } },
        React.createElement('span', { style: { width: 8, height: 8, borderRadius: '50%', background: '#52c41a' } }),
        React.createElement('div', null, React.createElement('div', { style: { fontSize: 22, fontWeight: 600, lineHeight: 1, fontVariantNumeric: 'tabular-nums' } }, total),
          React.createElement('div', { className: 'cell-sub', style: { marginTop: 2 } }, '符合条件志愿者（已实名）')))),
    React.createElement(Table, { columns: cols, data: rows, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra, onRowClick: function (v) { setDetail(v); },
      pagination: { total: total, page: page, size: pageSize, onChange: setPage } }),

    detail ? React.createElement(VolunteerDetailDrawer, { row: detail, canEdit: canEdit, onClose: function () { setDetail(null); },
      onEdit: function () { var d = detail; setDetail(null); setEdit(d); } }) : null,
    edit ? React.createElement(VolunteerEditDrawer, { row: edit, squadOpts: squadOpts, onClose: function () { setEdit(null); },
      onSaved: function () { setEdit(null); load(); } }) : null);
}

function VolunteerDetailDrawer(props) {
  var row = props.row;
  var [d, setD] = useState(null);
  var [loading, setLoading] = useState(true);
  useEffect(function () {
    API.get('/a/user/volunteers/' + row.id).then(function (x) { setD(x || {}); })
      .catch(function () { setD({}); }).then(function () { setLoading(false); });
  }, [row.id]);
  var v = Object.assign({}, row, d || {});       // 列表行先占位，详情到位后覆盖
  function val(x, fb) { return loading ? '加载中…' : (x != null && x !== '' ? x : (fb || '—')); }

  return React.createElement(Drawer, { open: true, width: 'wide', title: (v.name || '') + ' 的资料', sub: 'GET /a/user/volunteers/' + row.id, onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '关闭'),
      props.canEdit ? React.createElement(Btn, { type: 'primary', icon: 'edit', onClick: props.onEdit }, '修改资料') : null) },
    React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 14, marginBottom: 18 } },
      React.createElement(Avatar, { name: v.name || '?', size: 'lg' }),
      React.createElement('div', null,
        React.createElement('div', { style: { fontSize: 16, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 } }, v.name,
          React.createElement(StatusTag, { map: 'acct', value: v.status, dot: true }), v.managerFlag ? React.createElement(Tag, { color: 'purple' }, '管理团队') : null),
        React.createElement('div', { className: 'cell-sub mono' }, (v.phone || '—') + ' · ID ' + row.id))),
    React.createElement('div', { style: { display: 'flex', gap: 12, marginBottom: 18 } },
      [['服务时长', (v.hours != null ? v.hours : 0) + ' h'], ['累计积分', v.points != null ? v.points : 0], ['参与活动', (v.activities != null ? v.activities : 0) + ' 次']].map(function (s, i) {
        return React.createElement('div', { key: i, style: { flex: 1, textAlign: 'center', padding: '12px 0', background: 'var(--fill-1)', borderRadius: 8 } },
          React.createElement('div', { style: { fontSize: 20, fontWeight: 600, fontVariantNumeric: 'tabular-nums' } }, s[1]),
          React.createElement('div', { className: 'cell-sub', style: { marginTop: 2 } }, s[0]));
      })),
    React.createElement(Descriptions, { items: [
      { label: '性别', value: val(v.gender) }, { label: '身份证尾号', value: loading ? '加载中…' : (v.idTail ? React.createElement('span', { className: 'mono' }, '****' + v.idTail) : '—') },
      { label: '学校', value: val(v.school) }, { label: '年级', value: val(v.grade) },
      { label: '政治面貌', value: val(v.political) }, { label: '归属分队', value: v.squad || '未归属' },
      { label: '所在小组', value: v.group || '无' }, { label: '注册时间', value: val(v.registerTime) },
      { label: '紧急联系人', value: val(v.emergency) },
    ] }));
}

function VolunteerEditDrawer(props) {
  var row = props.row;
  // 编辑用单个分队下拉：去掉「全部」、补「未归属」
  var editSquadOpts = [{ value: '', label: '未归属' }].concat((props.squadOpts || []).filter(function (o) { return o.value !== 'all'; }));
  var [loading, setLoading] = useState(true);
  var [saving, setSaving] = useState(false);
  var [f, setF] = useState({
    realName: row.name || '', phone: row.phone || '', school: row.school || '',
    gender: row.genderCode != null ? String(row.genderCode) : '',
    political: row.politicalCode != null ? String(row.politicalCode) : '',
    grade: row.gradeCode != null ? String(row.gradeCode) : '',
    squadId: row.squadId != null ? String(row.squadId) : '',
    emergencyContactName: '', emergencyContactPhone: '',
  });
  function set(k, val) { setF(function (p) { var n = Object.assign({}, p); n[k] = val; return n; }); }

  // 拉详情补全列表行没有的字段（紧急联系人）
  useEffect(function () {
    API.get('/a/user/volunteers/' + row.id).then(function (d) {
      if (d) setF(function (p) { return Object.assign({}, p, {
        emergencyContactName: d.emergencyContactName || '', emergencyContactPhone: d.emergencyContactPhone || '' }); });
    }).catch(function () {}).then(function () { setLoading(false); });
  }, [row.id]);

  function numOrNull(s) { return s === '' || s == null ? null : Number(s); }
  function save() {
    if (!f.realName.trim()) { window.message.error('请填写姓名'); return; }
    var payload = {
      realName: f.realName.trim(), phone: f.phone,
      gender: numOrNull(f.gender), political: numOrNull(f.political), grade: numOrNull(f.grade),
      squadId: f.squadId === '' ? null : Number(f.squadId),
      emergencyContactName: f.emergencyContactName, emergencyContactPhone: f.emergencyContactPhone,
    };
    setSaving(true);
    API.put('/a/user/volunteers/' + row.id, payload).then(function () {
      window.message.success('已保存志愿者资料'); props.onSaved();
    }).catch(function () {}).then(function () { setSaving(false); });
  }

  return React.createElement(Drawer, { open: true, width: 'wide', title: '修改志愿者 · ' + (row.name || ''), sub: 'PUT /a/user/volunteers/' + row.id + '（全量更新，仅超管）', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', loading: saving, onClick: save }, '保存')) },
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '姓名', required: true }, React.createElement(Input, { value: f.realName, onChange: function (x) { set('realName', x); } })),
      React.createElement(Field, { label: '手机号' }, React.createElement(Input, { value: f.phone, onChange: function (x) { set('phone', x); }, placeholder: '留空将清空主手机号' }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '性别' }, React.createElement(Select, { value: f.gender, options: [{ value: '', label: '未设置' }].concat(VOL_GENDER_OPTS), onChange: function (x) { set('gender', x); } })),
      React.createElement(Field, { label: '政治面貌' }, React.createElement(Select, { value: f.political, options: [{ value: '', label: '未设置' }].concat(VOL_POLITICAL_OPTS), onChange: function (x) { set('political', x); } }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '学校' }, React.createElement(Input, { value: f.school, onChange: function (x) { set('school', x); } })),
      React.createElement(Field, { label: '年级' }, React.createElement(Select, { value: f.grade, options: [{ value: '', label: '未设置' }].concat(VOL_GRADE_OPTS), onChange: function (x) { set('grade', x); } }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '归属分队' }, React.createElement(Select, { value: f.squadId, options: editSquadOpts, onChange: function (x) { set('squadId', x); } })),
      React.createElement(Field, { label: '紧急联系人' }, React.createElement(Input, { value: f.emergencyContactName, onChange: function (x) { set('emergencyContactName', x); }, disabled: loading, placeholder: loading ? '加载中…' : '' }))),
    React.createElement(Field, { label: '紧急联系电话' }, React.createElement(Input, { value: f.emergencyContactPhone, onChange: function (x) { set('emergencyContactPhone', x); }, disabled: loading, placeholder: loading ? '加载中…' : '留空将清空' })),
    React.createElement(Alert, { type: 'info', style: { marginTop: 4 } }, '「管理团队」标记与活动域授权请在「志愿者标记与授权」页操作；所在小组由小组管理维护；账号停用/恢复在列表「更多」中。'));
}

window.VolunteersPage = VolunteersPage;
