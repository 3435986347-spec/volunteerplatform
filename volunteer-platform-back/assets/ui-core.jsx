/* ============================================================
   UI 核心原语：按钮 / 标签 / 表单控件 / 状态映射 / 卡片
   导出到 window 供其它 babel 脚本共享。
   ============================================================ */
/* hooks (useState/useEffect/useRef/useCallback/createContext/useContext)
   are provided as globals by a plain <script> in the HTML head before babel. */

/* ---------- hooks ---------- */
function useClickOutside(ref, onOut, active) {
  useEffect(function () {
    if (active === false) return;
    function h(e) { if (ref.current && !ref.current.contains(e.target)) onOut(); }
    document.addEventListener('mousedown', h);
    return function () { document.removeEventListener('mousedown', h); };
  }, [active]);
}

/* ---------- Button ---------- */
function Btn(props) {
  var cls = ['btn'];
  if (props.type === 'primary') cls.push('primary');
  if (props.type === 'text') cls.push('text');
  if (props.danger) cls.push('danger');
  if (props.size) cls.push(props.size);
  if (props.block) cls.push('block');
  if (props.className) cls.push(props.className);
  return React.createElement('button', {
    className: cls.join(' '), disabled: props.disabled,
    onClick: props.disabled ? undefined : props.onClick, title: props.title,
    style: props.style,
  }, props.icon ? React.createElement(Icon, { name: props.icon, size: 15 }) : null, props.children);
}

/* ---------- Tag ---------- */
function Tag(props) {
  var cls = ['tag', props.color || 'default'];
  if (props.lg) cls.push('lg');
  if (props.className) cls.push(props.className);
  return React.createElement('span', { className: cls.join(' '), style: props.style },
    props.dot ? React.createElement('span', { className: 'dot' }) : null, props.children);
}

/* ---------- 状态映射表（值 → 文案 + 颜色，未知兜底灰） ---------- */
var MAPS = {
  audit: { 0: ['待审', 'warning'], 1: ['通过', 'success'], 2: ['拒绝', 'error'] },
  enroll: { 0: ['待审核', 'warning'], 1: ['已通过', 'success'], 2: ['已拒绝', 'error'] },
  run: { 0: ['未开始', 'default'], 1: ['进行中', 'processing'], 2: ['已结束', 'success'] },
  attend: { 1: ['正常', 'success'], 2: ['请假', 'warning'], 3: ['迟到', 'gold'], 4: ['缺席', 'error'] },
  secretary: { 0: ['未确认', 'default'], 1: ['已确认', 'success'] },
  points: { 0: ['未发放', 'default'], 1: ['已发放', 'success'] },
  pubStatus: { 0: ['草稿', 'default'], 1: ['已发布', 'success'] },
  shelf: { 0: ['已下架', 'default'], 1: ['已上架', 'success'] },
  acct: { 0: ['已禁用', 'default'], 1: ['正常使用', 'success'] },
  groupStatus: { 0: ['待审批', 'warning'], 1: ['正常', 'success'], 2: ['已解散', 'default'] },
  linkType: { 0: ['无跳转', 'default'], 1: ['网页/推文', 'blue'], 2: ['小程序', 'cyan'] },
  leaderType: { 1: ['报名志愿者', 'blue'], 2: ['管理团队', 'purple'] },
  pubReview: { 4: ['待审核', 'warning'], 1: ['已通过/上线', 'success'], 5: ['已驳回', 'error'] },
};
function StatusTag(props) {
  var m = MAPS[props.map] || {};
  var entry = m[props.value] || [String(props.value), 'default'];
  return React.createElement(Tag, { color: entry[1], dot: props.dot, lg: props.lg }, entry[0]);
}

/* ---------- Avatar ---------- */
var AVA_COLORS = ['#1677ff', '#52c41a', '#fa8c16', '#722ed1', '#13a8a8', '#eb2f96', '#f5222d'];
function Avatar(props) {
  var name = props.name || '?';
  var ch = name.slice(-2);
  var idx = 0; for (var i = 0; i < name.length; i++) idx += name.charCodeAt(i);
  var cls = ['avatar']; if (props.size) cls.push(props.size);
  return React.createElement('span', { className: cls.join(' '),
    style: { background: props.color || AVA_COLORS[idx % AVA_COLORS.length] } }, ch);
}
function UserCell(props) {
  return React.createElement('div', { className: 'u-cell' },
    React.createElement(Avatar, { name: props.name, size: props.size }),
    React.createElement('div', { className: 'u-meta' },
      React.createElement('b', null, props.name),
      props.sub ? React.createElement('span', null, props.sub) : null));
}

/* ---------- Field wrapper ---------- */
function Field(props) {
  var cls = ['field']; if (props.error) cls.push('has-error'); if (props.className) cls.push(props.className);
  return React.createElement('div', { className: cls.join(' '), style: props.style },
    props.label ? React.createElement('label', null,
      props.required ? React.createElement('span', { className: 'req' }, '*') : null, props.label) : null,
    props.children,
    props.hint ? React.createElement('div', { className: 'hint' }, props.hint) : null,
    props.error ? React.createElement('div', { className: 'err-msg' }, props.error) : null);
}

/* ---------- Input / Textarea / Search ---------- */
function Input(props) {
  return React.createElement('input', {
    className: 'input' + (props.sm ? ' sm' : '') + (props.className ? ' ' + props.className : ''),
    type: props.type || 'text', value: props.value == null ? '' : props.value,
    placeholder: props.placeholder, onChange: function (e) { props.onChange && props.onChange(e.target.value); },
    disabled: props.disabled, style: props.style, maxLength: props.maxLength,
  });
}
function Textarea(props) {
  return React.createElement('textarea', {
    className: 'textarea', value: props.value == null ? '' : props.value, placeholder: props.placeholder,
    onChange: function (e) { props.onChange && props.onChange(e.target.value); },
    rows: props.rows || 4, style: props.style, maxLength: props.maxLength,
  });
}
function Search(props) {
  return React.createElement('div', { className: 'input-affix', style: { width: props.width || 240 } },
    React.createElement('span', { className: 'prefix' }, React.createElement(Icon, { name: 'search', size: 15 })),
    React.createElement('input', {
      className: 'input', value: props.value == null ? '' : props.value, placeholder: props.placeholder || '搜索',
      onChange: function (e) { props.onChange && props.onChange(e.target.value); },
      onKeyDown: function (e) { if (e.key === 'Enter' && props.onSearch) props.onSearch(props.value); },
    }));
}

/* ---------- Select ---------- */
function Select(props) {
  var ref = useRef(); var [open, setOpen] = useState(false);
  useClickOutside(ref, function () { setOpen(false); }, open);
  var opts = props.options || [];
  var cur = opts.filter(function (o) { return o.value === props.value; })[0];
  return React.createElement('div', { className: 'select' + (props.inline ? ' inline' : ''), ref: ref,
    style: { width: props.width || (props.inline ? 'auto' : '100%'), minWidth: props.inline ? (props.minWidth || 140) : undefined } },
    React.createElement('div', { className: 'select-trigger' + (open ? ' open' : '') + (props.inline ? ' inline' : ''),
      onClick: function () { if (!props.disabled) setOpen(!open); } },
      React.createElement('span', { className: 'val' + (cur ? '' : ' placeholder') }, cur ? cur.label : (props.placeholder || '请选择')),
      React.createElement('span', { className: 'caret' }, React.createElement(Icon, { name: 'chevronDown', size: 13 }))),
    open ? React.createElement('div', { className: 'select-menu' }, opts.map(function (o) {
      return React.createElement('div', { key: o.value, className: 'select-opt' + (o.value === props.value ? ' sel' : ''),
        onClick: function () { props.onChange && props.onChange(o.value); setOpen(false); } },
        o.color ? React.createElement('span', { className: 'dot', style: { width: 6, height: 6, borderRadius: '50%', background: o.color } }) : null,
        React.createElement('span', null, o.label),
        o.value === props.value ? React.createElement('span', { className: 'ck' }, React.createElement(Icon, { name: 'check', size: 14 })) : null);
    })) : null);
}

/* ---------- Radio ---------- */
function RadioGroup(props) {
  var opts = props.options || [];
  if (props.button) {
    return React.createElement('div', { className: 'radio-group', style: { gap: 0 } }, opts.map(function (o) {
      return React.createElement('div', { key: o.value, className: 'radio-btn' + (o.value === props.value ? ' on' : ''),
        onClick: function () { props.onChange && props.onChange(o.value); } }, o.label);
    }));
  }
  return React.createElement('div', { className: 'radio-group', style: props.vertical ? { flexDirection: 'column', gap: 8 } : null }, opts.map(function (o) {
    return React.createElement('div', { key: o.value, className: 'radio' + (o.value === props.value ? ' on' : ''),
      onClick: function () { props.onChange && props.onChange(o.value); } },
      React.createElement('span', { className: 'dot' }), React.createElement('span', null, o.label));
  }));
}

/* ---------- Checkbox ---------- */
function Checkbox(props) {
  var cls = ['checkbox']; if (props.checked) cls.push('on'); if (props.indeterminate) cls.push('indet');
  return React.createElement('label', { className: cls.join(' '),
    onClick: function (e) { e.preventDefault(); props.onChange && props.onChange(!props.checked); } },
    React.createElement('span', { className: 'box' }), props.children ? React.createElement('span', null, props.children) : null);
}

/* ---------- Switch ---------- */
function Switch(props) {
  return React.createElement('span', { className: 'switch' + (props.checked ? ' on' : '') + (props.sm ? ' sm' : ''),
    onClick: function () { if (!props.disabled) props.onChange && props.onChange(!props.checked); } });
}

/* ---------- Card / PageHead / Toolbar ---------- */
function Card(props) {
  return React.createElement('div', { className: 'card' + (props.className ? ' ' + props.className : ''), style: props.style },
    (props.title || props.extra) ? React.createElement('div', { className: 'card-head' },
      props.title ? React.createElement('h3', null, props.title) : null,
      props.sub ? React.createElement('span', { className: 'sub' }, props.sub) : null,
      props.extra ? React.createElement('div', { className: 'head-right' }, props.extra) : null) : null,
    React.createElement('div', { className: 'card-body' + (props.flush ? ' flush' : '') }, props.children));
}
function PageHead(props) {
  return React.createElement('div', { className: 'page-head' },
    React.createElement('div', { className: 'ph-text' },
      React.createElement('h1', null, props.title),
      props.desc ? React.createElement('p', null, props.desc) : null),
    props.actions ? React.createElement('div', { className: 'ph-actions' }, props.actions) : null);
}
function Toolbar(props) {
  return React.createElement('div', { className: 'toolbar' },
    React.createElement('div', { className: 'filters' }, props.filters),
    props.actions ? React.createElement('div', { className: 'toolbar-actions' }, props.actions) : null);
}

/* ---------- Tabs ---------- */
function Tabs(props) {
  return React.createElement('div', { className: 'tabs', style: props.style }, props.items.map(function (it) {
    return React.createElement('div', { key: it.key, className: 'tab' + (it.key === props.active ? ' active' : ''),
      onClick: function () { props.onChange(it.key); } },
      it.label, it.count != null ? React.createElement('span', { className: 'tab-count' }, '(' + it.count + ')') : null);
  }));
}

/* ---------- Alert ---------- */
function Alert(props) {
  var icon = props.type === 'warning' || props.type === 'warning-strong' ? 'warning' : 'info';
  return React.createElement('div', { className: 'alert ' + (props.type || 'info'), style: props.style },
    React.createElement('span', { className: 'a-icon' }, React.createElement(Icon, { name: icon, size: 16 })),
    React.createElement('div', null,
      props.title ? React.createElement('div', { className: 'a-title' }, props.title) : null,
      React.createElement('div', null, props.children)));
}

/* ---------- Descriptions ---------- */
function Descriptions(props) {
  return React.createElement('div', { className: 'desc', style: props.style }, props.items.map(function (it, i) {
    return [React.createElement('div', { className: 'dt', key: 'k' + i }, it.label),
      React.createElement('div', { className: 'dd', key: 'v' + i }, it.value)];
  }));
}

Object.assign(window, {
  useClickOutside: useClickOutside, Btn: Btn, Tag: Tag, StatusTag: StatusTag, MAPS: MAPS,
  Avatar: Avatar, UserCell: UserCell, Field: Field, Input: Input, Textarea: Textarea, Search: Search,
  Select: Select, RadioGroup: RadioGroup, Checkbox: Checkbox, Switch: Switch,
  Card: Card, PageHead: PageHead, Toolbar: Toolbar, Tabs: Tabs, Alert: Alert, Descriptions: Descriptions,
});
