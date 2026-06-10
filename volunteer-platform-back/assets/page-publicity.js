/* ============================================================
   信息公示 · 轮播图 / 公告 / 文件下载（真实接口 /a/publicity/*）
   轮播图与公告封面共用「上传 + 跳转配置」。后端 VO 字段：
   Banner {id,title,imageUrl,linkType,linkUrl,sort,status}
   Announcement {id,title,summary,content,coverImageUrl,linkType,linkUrl,status,publishTime}
   PublicityFile {id,fileName,fileUrl,fileType,fileSize,downloadable,sort}
   注：后端 VO 无 views(公告浏览数)/downloads(下载次数)/上传时间，故对应列显示「—」。
   ============================================================ */

function fmtSize(bytes) {
  bytes = Number(bytes);
  if (!bytes) return '—';
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
}
function fmtDate(s) { return s ? String(s).slice(0, 10) : '—'; }
function isRealUrl(u) { return u && /^(https?:|\/)/.test(u); }
function imgCell(url, w, h, label) {
  return React.createElement('div', { style: { width: w, height: h, borderRadius: 6, overflow: 'hidden', background: isRealUrl(url) ? '#f0f0f0' : gradOf(url), display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'rgba(255,255,255,.85)', fontSize: 10, fontWeight: 600 } },
    isRealUrl(url) ? React.createElement('img', { src: url, alt: '', style: { width: '100%', height: '100%', objectFit: 'cover' }, onError: function (e) { e.target.style.display = 'none'; } }) : label);
}

/* 跳转配置子组件：linkType 0无 / 1网页(推文) / 2小程序 */
function LinkConfig(props) {
  return React.createElement('div', null,
    React.createElement(Field, { label: '跳转类型' },
      React.createElement(RadioGroup, { button: true, value: props.linkType, onChange: props.onTypeChange,
        options: [{ value: 0, label: '无跳转' }, { value: 1, label: '网页 / 推文' }, { value: 2, label: '小程序' }] })),
    props.linkType === 1 ? React.createElement(Field, { label: '网页地址', required: true, hint: '「跳转推文」即填公众号文章链接' },
      React.createElement(Input, { value: props.linkUrl, onChange: props.onUrlChange, placeholder: 'https://mp.weixin.qq.com/s/...' })) : null,
    props.linkType === 2 ? React.createElement(Field, { label: '小程序 path', required: true, hint: '可附 appId' },
      React.createElement(Input, { value: props.linkUrl, onChange: props.onUrlChange, placeholder: 'pages/activity/detail?id=1003' })) : null);
}

/* ---------- 6a. 轮播图 ---------- */
function BannersPage(props) {
  var [list, setList] = useState([]);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [edit, setEdit] = useState(null);
  function load() {
    setLoading(true); setErr(false);
    API.get('/a/publicity/banners', { page: 1, size: 100 }).then(function (res) {
      setList(res.records || []); // 后端按 sort 倒序返回（数字越大越靠前），不在前端重排，与志愿者端展示语义一致
    }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);
  function del(b) {
    window.confirmDialog({ title: '删除轮播图「' + b.title + '」？', danger: true, okText: '删除' }).then(function (ok) {
      if (ok) API.del('/a/publicity/banners/' + b.id).then(function () { window.message.success('已删除'); load(); });
    });
  }
  function toggleStatus(b) {
    var to = b.status === 1 ? 0 : 1;
    API.put('/a/publicity/banners/' + b.id, { title: b.title, imageUrl: b.imageUrl, linkType: b.linkType, linkUrl: b.linkUrl, sort: b.sort, status: to })
      .then(function () { window.message.success(to === 1 ? '已上架' : '已下架'); load(); });
  }
  var cols = [
    { title: '图片', key: 'img', width: 130, render: function (b) { return imgCell(b.imageUrl, 104, 44, '750×307'); } },
    { title: '标题', key: 'title', render: function (b) { return React.createElement('span', { className: 'strong' }, b.title); } },
    { title: '跳转', key: 'link', width: 200, render: function (b) {
      return React.createElement('div', null, React.createElement(StatusTag, { map: 'linkType', value: b.linkType }),
        b.linkUrl ? React.createElement('div', { className: 'cell-sub mono', style: { maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, b.linkUrl) : null);
    }},
    { title: '排序', key: 'sort', width: 80, align: 'center', render: function (b) { return React.createElement('span', { className: 'mono' }, b.sort); } },
    { title: '上/下架', key: 'status', width: 90, align: 'center', render: function (b) {
      return React.createElement(Switch, { sm: true, checked: b.status === 1, onChange: function () { toggleStatus(b); } });
    }},
    { title: '操作', key: 'act', width: 120, render: function (b) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setEdit({ data: b }); } }, '编辑'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link danger', onClick: function () { del(b); } }, '删除'));
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '轮播图', desc: '首页轮播图管理。建议比例 750×307（≈2.44:1）；可设排序与上/下架。',
      actions: React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEdit({ data: {} }); } }, '新增轮播图') }),
    React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra, pagination: false }),
    edit ? React.createElement(BannerFormDrawer, { data: edit.data, onClose: function () { setEdit(null); }, onSaved: load }) : null);
}
function BannerFormDrawer(props) {
  var d = props.data;
  var [f, setF] = useState({ title: d.title || '', imageUrl: d.imageUrl || '', linkType: d.linkType || 0, linkUrl: d.linkUrl || '', sort: d.sort != null ? d.sort : 1, status: d.status != null ? d.status : 1 });
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }
  function save() {
    if (!f.title) { window.message.error('请填写标题'); return; }
    if (!f.imageUrl) { window.message.error('请上传轮播图片'); return; }
    setSaving(true);
    var body = { title: f.title, imageUrl: f.imageUrl, linkType: Number(f.linkType) || 0, linkUrl: f.linkUrl, sort: Number(f.sort) || 0, status: Number(f.status) };
    var p = d.id ? API.put('/a/publicity/banners/' + d.id, body) : API.post('/a/publicity/banners', body);
    p.then(function () { window.message.success('已保存轮播图'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: d.id ? '编辑轮播图' : '新增轮播图', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: save }, saving ? '保存中…' : '保存')) },
    React.createElement(Field, { label: '标题', required: true }, React.createElement(Input, { value: f.title, onChange: function (v) { set('title', v); }, placeholder: '轮播图标题' })),
    React.createElement(Field, { label: '轮播图片', required: true }, React.createElement(ImageField, { preset: 'banner', dir: 'banner', previewW: 234, value: f.imageUrl, onChange: function (v) { set('imageUrl', v); } })),
    React.createElement(LinkConfig, { linkType: f.linkType, linkUrl: f.linkUrl, onTypeChange: function (v) { set('linkType', v); }, onUrlChange: function (v) { set('linkUrl', v); } }),
    React.createElement('div', { className: 'field-row' },
      React.createElement(Field, { label: '排序', hint: '数字越大越靠前' }, React.createElement(Input, { type: 'number', value: f.sort, onChange: function (v) { set('sort', v); } })),
      React.createElement(Field, { label: '状态' }, React.createElement(Select, { value: f.status, options: [{ value: 1, label: '上架' }, { value: 0, label: '下架' }], onChange: function (v) { set('status', v); } }))));
}

/* ---------- 6b. 公告 ---------- */
function AnnouncementsPage(props) {
  var [list, setList] = useState([]);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [edit, setEdit] = useState(null);
  function load() {
    setLoading(true); setErr(false);
    API.get('/a/publicity/announcements', { page: 1, size: 100 }).then(function (res) {
      setList(res.records || []);
    }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);
  function del(a) {
    window.confirmDialog({ title: '删除公告「' + a.title + '」？', danger: true, okText: '删除' }).then(function (ok) {
      if (ok) API.del('/a/publicity/announcements/' + a.id).then(function () { window.message.success('已删除'); load(); });
    });
  }
  var cols = [
    { title: '标题', key: 'title', render: function (a) { return React.createElement('div', null, React.createElement('div', { className: 'strong' }, a.title), React.createElement('div', { className: 'cell-sub', style: { maxWidth: 420, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } }, a.summary)); } },
    { title: '跳转', key: 'link', width: 120, render: function (a) { return React.createElement(StatusTag, { map: 'linkType', value: a.linkType }); } },
    { title: '状态', key: 'status', width: 100, render: function (a) { return React.createElement(StatusTag, { map: 'pubStatus', value: a.status, dot: true }); } },
    { title: '浏览', key: 'views', width: 80, align: 'center', render: function () { return React.createElement('span', { className: 'muted' }, '—'); } },
    { title: '发布时间', key: 'time', width: 120, render: function (a) { return React.createElement('span', { className: 'cell-sub' }, fmtDate(a.publishTime)); } },
    { title: '操作', key: 'act', width: 120, render: function (a) {
      return React.createElement('div', { className: 'row-actions' },
        React.createElement('button', { className: 'btn-link', onClick: function () { setEdit({ data: a }); } }, '编辑'),
        React.createElement('span', { className: 'act-sep' }),
        React.createElement('button', { className: 'btn-link danger', onClick: function () { del(a); } }, '删除'));
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '公告', desc: '公告编辑，封面可配跳转。支持存草稿不公开、发布后志愿者端可见。',
      actions: React.createElement(Btn, { type: 'primary', icon: 'plus', onClick: function () { setEdit({ data: {} }); } }, '新建公告') }),
    React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra, pagination: false }),
    edit ? React.createElement(AnnFormDrawer, { data: edit.data, onClose: function () { setEdit(null); }, onSaved: load }) : null);
}
function AnnFormDrawer(props) {
  var d = props.data;
  var [f, setF] = useState({ title: d.title || '', summary: d.summary || '', cover: d.coverImageUrl || '', linkType: d.linkType || 0, linkUrl: d.linkUrl || '', content: d.content || '' });
  var [saving, setSaving] = useState(false);
  function set(k, v) { setF(function (p) { var n = Object.assign({}, p); n[k] = v; return n; }); }
  function save(status) {
    if (!f.title) { window.message.error('请填写标题'); return; }
    if (!f.content) { window.message.error('请填写正文'); return; }
    setSaving(true);
    var body = { title: f.title, summary: f.summary, coverImageUrl: f.cover, linkType: Number(f.linkType) || 0, linkUrl: f.linkUrl, content: f.content, status: status };
    var p = d.id ? API.put('/a/publicity/announcements/' + d.id, body) : API.post('/a/publicity/announcements', body);
    p.then(function () { window.message.success(status === 0 ? '已存为草稿' : '已发布公告'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, width: 'wide', title: d.id ? '编辑公告' : '新建公告', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { onClick: function () { save(0); } }, '存草稿'),
      React.createElement(Btn, { type: 'primary', onClick: function () { save(1); } }, saving ? '提交中…' : '发布')) },
    React.createElement(Field, { label: '标题', required: true }, React.createElement(Input, { value: f.title, onChange: function (v) { set('title', v); }, placeholder: '公告标题' })),
    React.createElement(Field, { label: '摘要' }, React.createElement(Textarea, { value: f.summary, onChange: function (v) { set('summary', v); }, rows: 2, placeholder: '一句话摘要，列表与卡片展示' })),
    React.createElement(Field, { label: '封面 / 插图' }, React.createElement(ImageField, { preset: 'cover', dir: 'announcement', previewW: 240, value: f.cover, onChange: function (v) { set('cover', v); } })),
    React.createElement(LinkConfig, { linkType: f.linkType, linkUrl: f.linkUrl, onTypeChange: function (v) { set('linkType', v); }, onUrlChange: function (v) { set('linkUrl', v); } }),
    React.createElement(Field, { label: '正文', required: true },
      React.createElement('textarea', { className: 'textarea', style: { minHeight: 200 }, value: f.content, onChange: function (e) { set('content', e.target.value); }, placeholder: '公告正文…' })));
}

/* ---------- 6c. 文件下载 ---------- */
function FilesPage(props) {
  var [list, setList] = useState([]);
  var [loading, setLoading] = useState(false);
  var [err, setErr] = useState(false);
  var [uploadOpen, setUploadOpen] = useState(false);
  var typeColor = { pdf: 'error', xlsx: 'success', xls: 'success', docx: 'blue', doc: 'blue' };
  function load() {
    setLoading(true); setErr(false);
    API.get('/a/publicity/files', { page: 1, size: 100 }).then(function (res) {
      setList(res.records || []);
    }).catch(function () { setErr(true); }).then(function () { setLoading(false); });
  }
  useEffect(load, []);
  function del(f) {
    window.confirmDialog({ title: '删除文件「' + f.fileName + '」？', danger: true, okText: '删除' }).then(function (ok) {
      if (ok) API.del('/a/publicity/files/' + f.id).then(function () { window.message.success('已删除'); load(); });
    });
  }
  function toggleAccess(f) {
    var enable = f.downloadable !== 1; // 后端 FileAccessDTO.downloadable 是 Boolean，发布尔值而非 0/1
    API.patch('/a/publicity/files/' + f.id + '/access', { downloadable: enable })
      .then(function () { window.message.success(enable ? '已开启下载' : '已关闭下载'); load(); });
  }
  var cols = [
    { title: '文件名', key: 'name', render: function (f) {
      var type = (f.fileType || '').toLowerCase();
      return React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 10 } },
        React.createElement('span', { className: 'sc-icon', style: { width: 32, height: 32, borderRadius: 8, background: 'var(--fill-2)', color: 'var(--text-2)', display: 'flex', alignItems: 'center', justifyContent: 'center' } }, React.createElement(Icon, { name: 'file', size: 16 })),
        React.createElement('div', null, React.createElement('div', { className: 'strong' }, f.fileName), React.createElement('div', { className: 'cell-sub' }, React.createElement(Tag, { color: typeColor[type] || 'default' }, (type || 'file').toUpperCase()), '　' + fmtSize(f.fileSize))));
    }},
    { title: '下载次数', key: 'downloads', width: 100, align: 'center', render: function () { return React.createElement('span', { className: 'muted' }, '—'); } },
    { title: '可下载', key: 'access', width: 100, align: 'center', render: function (f) {
      return React.createElement(Switch, { sm: true, checked: f.downloadable === 1, onChange: function () { toggleAccess(f); } });
    }},
    { title: '操作', key: 'act', width: 90, render: function (f) {
      return React.createElement('button', { className: 'btn-link danger', onClick: function () { del(f); } }, '删除');
    }},
  ];
  return React.createElement('div', { className: 'page page-wide' },
    React.createElement(PageHead, { title: '文件下载', desc: '协会公开文件管理。可单独开关每个文件的「可下载」状态。',
      actions: React.createElement(Btn, { type: 'primary', icon: 'upload', onClick: function () { setUploadOpen(true); } }, '上传文件') }),
    React.createElement(Table, { columns: cols, data: list, loading: loading, error: err, onRetry: load, density: props.density, zebra: props.zebra, pagination: false }),
    uploadOpen ? React.createElement(FileUploadDrawer, { onClose: function () { setUploadOpen(false); }, onSaved: load }) : null);
}
function FileUploadDrawer(props) {
  var [file, setFile] = useState(null);
  var [name, setName] = useState('');
  var [downloadable, setDownloadable] = useState(true);
  var [saving, setSaving] = useState(false);
  function onFile(f) { setFile(f); if (!name) setName(f.name); }
  function submit() {
    if (!file) { window.message.error('请先选择文件'); return; }
    setSaving(true);
    API.upload(file, 'file').then(function (r) {
      var ext = (file.name.split('.').pop() || '').toLowerCase();
      return API.post('/a/publicity/files', {
        fileName: name || r.name || file.name,
        fileUrl: r.url,
        fileType: ext,
        fileSize: Number(r.size) || file.size,
        downloadable: downloadable ? 1 : 0,
      });
    }).then(function () { window.message.success('已上传文件'); props.onSaved && props.onSaved(); props.onClose(); })
      .catch(function () {}).then(function () { setSaving(false); });
  }
  return React.createElement(Drawer, { open: true, title: '上传文件', onClose: props.onClose,
    footer: React.createElement(React.Fragment, null, React.createElement(Btn, { onClick: props.onClose }, '取消'),
      React.createElement(Btn, { type: 'primary', onClick: submit }, saving ? '上传中…' : '确认上传')) },
    React.createElement(DropUpload, { onFile: onFile, accept: '.pdf,.xlsx,.xls,.docx,.doc,.ppt,.pptx,.txt,.zip', icon: 'folder',
      main: file ? file.name : '点击选择文件', hint: '支持 pdf / xlsx / docx 等，单文件 ≤ 20MB' }),
    React.createElement('div', { style: { marginTop: 16 } },
      React.createElement(Field, { label: '显示名称' }, React.createElement(Input, { value: name, onChange: function (v) { setName(v); }, placeholder: '默认使用文件名' })),
      React.createElement(Field, { label: '默认可下载', style: { marginBottom: 0 } }, React.createElement(Switch, { checked: downloadable, onChange: function (v) { setDownloadable(v); } }))));
}

Object.assign(window, { BannersPage: BannersPage, AnnouncementsPage: AnnouncementsPage, FilesPage: FilesPage });
