/* ============================================================
   志愿者管理（GET /a/user/volunteers ...）—— url 文档 user 域
   列表(多筛选)/详情/修改(全量PUT)/停用·恢复(PATCH status)/删除/重置密码/导出
   ============================================================ */
function VolunteersPage(props) {
  var id = props.identity;
  var [kw, setKw] = useState('');
  var [gender, setGender] = useState('all');
  var [squad, setSquad] = useState('all');
  var [political, setPolitical] = useState('all');
  var [page, setPage] = useState(1);
  var [detail, setDetail] = useState(null);
  var [edit, setEdit] = useState(null);

  var squadOpts = [{ value: 'all', label: '全部分队' }].concat(HD.SQUADS.map(function (s) { return { value: s.name, label: s.name }; }), [{ value: '—', label: '未归属' }]);
  var polOpts = [{ value: 'all', label: '全部政治面貌' }, { value: '中共党员', label: '中共党员' }, { value: '共青团员', label: '共青团员' }, { value: '群众', label: '群众' }];
  var rows = HD.VOLUNTEERS.filter(function (v) {
    if (kw && v.name.indexOf(kw) < 0 && v.phone.indexOf(kw) < 0 && v.school.indexOf(kw) < 0) return false;
    if (gender !== 'all' && v.gender !== gender) return false;
    if (squad !== 'all' && v.squad !== squad) return false;
    if (political !== 'all' && v.political !== political) return false;
    return true;
  });
  var pageSize = 10;
  var paged = rows.slice((page - 1) * pageSize, page * pageSize);

  function toggleStatus(v) {
    var to = v.status === 1 ? 0 : 1;
    window.confirmDialog({ title: (to === 0 ? '禁用' : '启用') + ' ' + v.name + ' 的账号？', danger: to === 0,
      content: to === 0 ? '禁用后该志愿者无法登录小程序、不能报名活动。' : '启用后该志愿者可正常使用。' }).then(function (ok) {
      if (ok) window.message.success((to === 0 ? '已禁用' : '已启用') + '（PATCH /a/user/volunteers/' + v.id + '/status）');
    });
  }
  function del(v) {
    window.confirmDialog({ title: '删除志愿者「' + v.name + '」？', danger: true, okText: '删除', reason: true, reasonRequired: false, reasonLabel: '删除备注（可选）',
      content: '删除后该志愿者的账号与基础资料将被移除（服务记录按归档策略处理）。此操作不可逆。' }).then(function (vv) {
      if (vv) window.message.success('已删除志愿者');
    });
  }
  function resetPwd(v) {
    window.confirmDialog({ title: '重置 ' + v.name + ' 的密码？', content: '小程序为微信登录，此处重置用于异常情形的兜底登录凭据。' }).then(function (ok) {
      if (ok) window.message.success('已重置（POST …/volunteers/' + v.id + '/password/reset）');
    });
  }

  var cols = [
    { title: '志愿者', key: 'name', render: function (v) {
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
        React.createElement(Avatar, { name: v.name }),
        React.createElement('div', null,
          React.createElement('div', { style: { fontWeight: 500, display: 'flex', alignItems: 'center', gap: 6 } }, v.name,
            React.createElement('span', { className: 'muted', style: { fontWeight: 400, fontSize: 12 } }, v.gender),
            v.managerFlag ? React.createElement(Tag, { color: 'purple' }, '管理团队') : null),
          React.createElement('div', { className: 'cell-sub mono' }, v.phone)));
    }},
    { title: '学校 / 年级', key: 'school', width: 170, render: function (v) {
      return React.createElement('div', null, React.createElement('div', null, v.school), React.createElement('div', { className: 'cell-sub' }, v.grade + ' · ' + v.political));
    }},
    { title: '分队 / 小组', key: 'squad', width: 160, render: function (v) {
      return React.createElement('div', null,
        v.squad !== '—' ? React.createElement(Tag, { color: 'cyan' }, v.squad) : React.createElement('span', { className: 'muted' }, '未归属'),
        React.createElement('div', { className: 'cell-sub' }, v.group !== '—' ? v.group : '无小组'));
    }},
    { title: '服务时长', key: 'hours', width: 100, align: 'center', render: function (v) {
      return React.createElement('div', null, React.createElement('div', { style: { fontVariantNumeric: 'tabular-nums' } }, v.hours + ' h'),
        React.createElement('div', { className: 'cell-sub' }, v.points + ' 积分'));
    }},
    { title: '状态', key: 'status', width: 90, render: function (v) { return React.createElement(StatusTag, { map: 'acct', value: v.status, dot: true }); } },
    { title: '操作', key: 'act', width: 200, render: function (v) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setDetail(v); } }, '详情'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); setEdit(v); } }, '修改'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement(Dropdown, { items: [
          { icon: v.status === 1 ? 'lock' : 'checkCircle', label: v.status === 1 ? '禁用账号' : '启用账号', onClick: function () { toggleStatus(v); } },
          { icon: 'key', label: '重置密码', onClick: function () { resetPwd(v); } },
          { divider: true },
          { icon: 'trash', label: '删除', danger: true, onClick: function () { del(v); } },
        ], trigger: React.createElement('button', { className: 'btn-link', onClick: function (e) { e.stopPropagation(); } }, '更多 ▾') }));
    }},
  ];

  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '志愿者管理', desc: 'GET /a/user/volunteers —— 志愿者列表、详情、修改、停用/恢复、删除、重置密码、导出（支持 keyword/性别/分队/政治面貌/学校/年级 等筛选）。',
      actions: React.createElement(Btn, { icon: 'download', onClick: function () { window.message.success('正在导出志愿者 Excel（GET /a/user/volunteers/export）'); } }, '导出志愿者') }),
    React.createElement(Toolbar, { filters: React.createElement(React.Fragment, null,
      React.createElement(Search, { value: kw, onChange: function (v) { setKw(v); setPage(1); }, placeholder: '搜索姓名 / 手机号 / 学校', width: 240 }),
      React.createElement(Select, { inline: true, minWidth: 110, value: gender, onChange: function (v) { setGender(v); setPage(1); }, options: [{ value: 'all', label: '全部性别' }, { value: '男', label: '男' }, { value: '女', label: '女' }] }),
      React.createElement(Select, { inline: true, minWidth: 140, value: squad, onChange: function (v) { setSquad(v); setPage(1); }, options: squadOpts }),
      React.createElement(Select, { inline: true, minWidth: 150, value: political, onChange: function (v) { setPolitical(v); setPage(1); }, options: polOpts })) }),
    React.createElement('div', { style: { display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' } },
      [['志愿者总数', HD.VOLUNTEERS.length, '#1677ff'], ['管理团队', HD.VOLUNTEERS.filter(function (v) { return v.managerFlag; }).length, '#722ed1'], ['已禁用', HD.VOLUNTEERS.filter(function (v) { return v.status === 0; }).length, '#8c8c8c'], ['当前筛选', rows.length, '#52c41a']].map(function (s, i) {
        return React.createElement('div', { key: i, className: 'card', style: { flex: '1 1 160px', padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 12 } },
          React.createElement('span', { style: { width: 8, height: 8, borderRadius: '50%', background: s[2] } }),
          React.createElement('div', null, React.createElement('div', { style: { fontSize: 22, fontWeight: 600, lineHeight: 1, fontVariantNumeric: 'tabular-nums' } }, s[1]),
            React.createElement('div', { className: 'cell-sub', style: { marginTop: 2 } }, s[0])));
      })),
    React.createElement(Table, { columns: cols, data: paged, density: props.density, zebra: props.zebra, onRowClick: function (v) { setDetail(v); },
      pagination: { total: rows.length, page: page, size: pageSize, onChange: setPage } }),

    detail ? React.createElement(VolunteerDetailDrawer, { v: detail, onClose: function () { setDetail(null); },
      onEdit: function () { var d = detail; setDetail(null); setEdit(d); } }) : null,
    edit ? React.createElement(VolunteerEditDrawer, { v: edit, onClose: function () { setEdit(null); } }) : null);
}

function VolunteerDetailDrawer(props) {
  var v = props.v;
  return React.createElement(Drawer, { open: true, width: 'wide', title: v.name + ' 的资料', sub: 'GET /a/user/volunteers/' + v.id, onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '关闭'),
      React.createElement(Btn, { type: 'primary', icon: 'edit', onClick: props.onEdit }, '修改资料')) },
    React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 14, marginBottom: 18 } },
      React.createElement(Avatar, { name: v.name, size: 'lg' }),
      React.createElement('div', null,
        React.createElement('div', { style: { fontSize: 16, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 } }, v.name, React.createElement(StatusTag, { map: 'acct', value: v.status, dot: true }), v.managerFlag ? React.createElement(Tag, { color: 'purple' }, '管理团队') : null),
        React.createElement('div', { className: 'cell-sub mono' }, v.phone + ' · ID ' + v.id))),
    React.createElement('div', { style: { display: 'flex', gap: 12, marginBottom: 18 } },
      [['服务时长', v.hours + ' h'], ['累计积分', v.points], ['参与活动', v.activities + ' 次']].map(function (s, i) {
        return React.createElement('div', { key: i, style: { flex: 1, textAlign: 'center', padding: '12px 0', background: 'var(--fill-1)', borderRadius: 8 } },
          React.createElement('div', { style: { fontSize: 20, fontWeight: 600, fontVariantNumeric: 'tabular-nums' } }, s[1]),
          React.createElement('div', { className: 'cell-sub', style: { marginTop: 2 } }, s[0]));
      })),
    React.createElement(Descriptions, { items: [
      { label: '性别', value: v.gender }, { label: '身份证尾号', value: React.createElement('span', { className: 'mono' }, '****' + v.idTail) },
      { label: '学校', value: v.school }, { label: '年级', value: v.grade },
      { label: '政治面貌', value: v.political }, { label: '归属分队', value: v.squad !== '—' ? v.squad : '未归属' },
      { label: '所在小组', value: v.group !== '—' ? v.group : '无' }, { label: '注册时间', value: v.joinDate },
      { label: '紧急联系人', value: v.emergency },
    ] }));
}

function VolunteerEditDrawer(props) {
  var v = props.v;
  var [f, setF] = useState(Object.assign({}, v));
  function set(k, val) { setF(function (p) { var n = Object.assign({}, p); n[k] = val; return n; }); }
  return React.createElement(Drawer, { open: true, width: 'wide', title: '修改志愿者 · ' + v.name, sub: 'PUT /a/user/volunteers/' + v.id + '（全量更新）', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: function () { window.message.success('已保存志愿者资料'); props.onClose(); } }, '保存')) },
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '姓名', required: true }, React.createElement(Input, { value: f.name, onChange: function (x) { set('name', x); } })),
      React.createElement(Field, { label: '手机号', required: true }, React.createElement(Input, { value: f.phone, onChange: function (x) { set('phone', x); } }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '性别' }, React.createElement(Select, { value: f.gender, options: [{ value: '男', label: '男' }, { value: '女', label: '女' }], onChange: function (x) { set('gender', x); } })),
      React.createElement(Field, { label: '政治面貌' }, React.createElement(Select, { value: f.political, options: ['中共党员', '共青团员', '群众'].map(function (p) { return { value: p, label: p }; }), onChange: function (x) { set('political', x); } }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '学校' }, React.createElement(Input, { value: f.school, onChange: function (x) { set('school', x); } })),
      React.createElement(Field, { label: '年级' }, React.createElement(Input, { value: f.grade, onChange: function (x) { set('grade', x); } }))),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '归属分队' }, React.createElement(Select, { value: f.squad, options: HD.SQUADS.map(function (s) { return { value: s.name, label: s.name }; }).concat([{ value: '—', label: '未归属' }]), onChange: function (x) { set('squad', x); } })),
      React.createElement(Field, { label: '所在小组' }, React.createElement(Input, { value: f.group, onChange: function (x) { set('group', x); } }))),
    React.createElement(Field, { label: '紧急联系方式' }, React.createElement(Input, { value: f.emergency, onChange: function (x) { set('emergency', x); } })),
    React.createElement(Alert, { type: 'info', style: { marginTop: 4 } }, '「管理团队」标记与活动域授权请在「志愿者标记与授权」页操作；账号停用/恢复在列表「更多」中。'));
}

window.VolunteersPage = VolunteersPage;
