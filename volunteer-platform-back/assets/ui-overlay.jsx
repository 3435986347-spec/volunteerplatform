/* ============================================================
   UI 覆盖层 & 数据展示：表格 / 分页 / 抽屉 / 弹窗 / 确认 / 消息
   下拉菜单 / 步骤 / 时间线 / 原值→新值 / 三态 / 权限包裹 / 上传裁剪
   ============================================================ */

/* ---------- 全局 message 与 confirm（imperative） ---------- */
var _emitter = { fns: [] };
function _emit(ev) { _emitter.fns.forEach(function (f) { f(ev); }); }
window.message = {
  success: function (t) { _emit({ kind: 'toast', type: 'success', text: t }); },
  error: function (t) { _emit({ kind: 'toast', type: 'error', text: t }); },
  info: function (t) { _emit({ kind: 'toast', type: 'info', text: t }); },
  warning: function (t) { _emit({ kind: 'toast', type: 'warning', text: t }); },
};
window.confirmDialog = function (opts) { return new Promise(function (res) { _emit({ kind: 'confirm', opts: opts, res: res }); }); };

function OverlayHost() {
  var [toasts, setToasts] = useState([]);
  var [confirm, setConfirm] = useState(null);
  useEffect(function () {
    function handler(ev) {
      if (ev.kind === 'toast') {
        var id = Date.now() + Math.random();
        setToasts(function (t) { return t.concat([{ id: id, type: ev.type, text: ev.text }]); });
        setTimeout(function () { setToasts(function (t) { return t.filter(function (x) { return x.id !== id; }); }); }, 2600);
      } else if (ev.kind === 'confirm') {
        setConfirm({ opts: ev.opts, res: ev.res });
      }
    }
    _emitter.fns.push(handler);
    return function () { _emitter.fns = _emitter.fns.filter(function (f) { return f !== handler; }); };
  }, []);
  var icons = { success: 'checkCircle', error: 'closeCircle', info: 'info', warning: 'warning' };
  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'toast-wrap' }, toasts.map(function (t) {
      return React.createElement('div', { key: t.id, className: 'toast ' + t.type },
        React.createElement('span', { className: 't-icon' }, React.createElement(Icon, { name: icons[t.type], size: 16 })),
        React.createElement('span', null, t.text));
    })),
    confirm ? React.createElement(ConfirmModal, { opts: confirm.opts,
      onClose: function (v) { confirm.res(v); setConfirm(null); } }) : null);
}

function ConfirmModal(props) {
  var o = props.opts || {};
  var [reasonVal, setReasonVal] = useState('');
  var danger = o.danger;
  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'overlay', onClick: function () { props.onClose(false); } }),
    React.createElement('div', { className: 'modal' },
      React.createElement('div', { className: 'modal-head' },
        React.createElement('span', { className: 'modal-icon', style: { color: danger ? 'var(--error)' : 'var(--warning)' } },
          React.createElement(Icon, { name: danger ? 'closeCircle' : 'warning', size: 22 })),
        React.createElement('h3', null, o.title || '确认操作')),
      React.createElement('div', { className: 'modal-body with-icon' },
        o.content ? React.createElement('div', { style: { marginBottom: o.reason ? 14 : 0 } }, o.content) : null,
        o.reason ? React.createElement(Field, { label: o.reasonLabel || '原因', required: o.reasonRequired, style: { marginBottom: 0 } },
          React.createElement(Textarea, { value: reasonVal, onChange: setReasonVal, rows: 3, placeholder: o.reasonPlaceholder || '请填写原因（将记录并通知申请人）' })) : null),
      React.createElement('div', { className: 'modal-foot' },
        React.createElement(Btn, { onClick: function () { props.onClose(false); } }, o.cancelText || '取消'),
        React.createElement(Btn, { type: 'primary', danger: danger,
          disabled: o.reason && o.reasonRequired && !reasonVal.trim(),
          onClick: function () { props.onClose(o.reason ? { ok: true, reason: reasonVal } : true); } }, o.okText || '确定'))));
}

/* ---------- Table ---------- */
function Table(props) {
  var cols = props.columns, data = props.data || [];
  var cls = ['tbl']; if (props.density === 'compact') cls.push('compact'); if (props.zebra) cls.push('zebra');
  return React.createElement('div', { className: 'table-wrap' },
    React.createElement('div', { className: 'table-scroll' },
      React.createElement('table', { className: cls.join(' ') },
        React.createElement('thead', null, React.createElement('tr', null, cols.map(function (c, i) {
          return React.createElement('th', { key: i, style: { width: c.width, textAlign: c.align },
            className: c.align === 'right' ? 'col-right' : c.align === 'center' ? 'col-center' : '' }, c.title);
        }))),
        React.createElement('tbody', null,
          props.loading ? React.createElement('tr', null, React.createElement('td', { colSpan: cols.length },
            React.createElement(LoadingState, null))) :
          props.error ? React.createElement('tr', null, React.createElement('td', { colSpan: cols.length },
            React.createElement(ErrorState, { onRetry: props.onRetry }))) :
          data.length === 0 ? React.createElement('tr', null, React.createElement('td', { colSpan: cols.length },
            React.createElement(EmptyState, { text: props.emptyText }))) :
          data.map(function (row, ri) {
            return React.createElement('tr', { key: row.id != null ? row.id : ri,
              onClick: props.onRowClick ? function () { props.onRowClick(row); } : undefined,
              style: props.onRowClick ? { cursor: 'pointer' } : null },
              cols.map(function (c, ci) {
                return React.createElement('td', { key: ci, style: { textAlign: c.align },
                  className: c.align === 'right' ? 'col-right' : c.align === 'center' ? 'col-center' : '' },
                  c.render ? c.render(row, ri) : row[c.key]);
              }));
          })))),
    props.pagination !== false && !props.loading && !props.error && data.length > 0 ?
      React.createElement(Pagination, props.pagination || { total: data.length }) : null);
}

/* ---------- Pagination ---------- */
function Pagination(props) {
  var total = props.total || 0, size = props.size || 10, page = props.page || 1;
  var pages = Math.max(1, Math.ceil(total / size));
  var nums = [];
  for (var i = 1; i <= pages; i++) {
    if (i === 1 || i === pages || Math.abs(i - page) <= 1) nums.push(i);
    else if (nums[nums.length - 1] !== '...') nums.push('...');
  }
  function go(p) { if (p >= 1 && p <= pages && p !== page && props.onChange) props.onChange(p); }
  return React.createElement('div', { className: 'pagination' },
    React.createElement('span', { className: 'total' }, '共 ' + total + ' 条'),
    React.createElement('span', { className: 'page-btn' + (page <= 1 ? ' disabled' : ''), onClick: function () { go(page - 1); } },
      React.createElement(Icon, { name: 'chevronLeft', size: 14 })),
    nums.map(function (n, i) {
      if (n === '...') return React.createElement('span', { key: 'e' + i, className: 'page-ellipsis' }, '···');
      return React.createElement('span', { key: n, className: 'page-btn' + (n === page ? ' active' : ''), onClick: function () { go(n); } }, n);
    }),
    React.createElement('span', { className: 'page-btn' + (page >= pages ? ' disabled' : ''), onClick: function () { go(page + 1); } },
      React.createElement(Icon, { name: 'chevronRight', size: 14 })));
}

/* ---------- States ---------- */
function EmptyState(props) {
  return React.createElement('div', { className: 'state-block' },
    React.createElement('span', { className: 'state-illu' }, React.createElement(Icon, { name: 'inbox', size: 46, sw: 1.2 })),
    React.createElement('div', { className: 'state-title' }, props.text || '暂无数据'),
    props.sub ? React.createElement('div', { className: 'state-sub' }, props.sub) : null,
    props.action || null);
}
function LoadingState(props) {
  return React.createElement('div', { className: 'state-block' },
    React.createElement('div', { className: 'spinner' }),
    React.createElement('div', { className: 'state-title', style: { fontSize: 14 } }, props.text || '加载中…'));
}
function ErrorState(props) {
  return React.createElement('div', { className: 'state-block' },
    React.createElement('span', { className: 'state-illu', style: { color: 'var(--error)' } }, React.createElement(Icon, { name: 'closeCircle', size: 42, sw: 1.4 })),
    React.createElement('div', { className: 'state-title' }, '请求失败'),
    React.createElement('div', { className: 'state-sub' }, '加载数据时出现问题，请稍后重试。'),
    React.createElement(Btn, { icon: 'refresh', onClick: props.onRetry }, '重新加载'));
}

/* ---------- Drawer ---------- */
function Drawer(props) {
  if (!props.open) return null;
  var cls = ['drawer']; if (props.width === 'wide') cls.push('wide'); if (props.width === 'xwide') cls.push('xwide');
  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'overlay', onClick: props.onClose }),
    React.createElement('div', { className: cls.join(' ') },
      React.createElement('div', { className: 'drawer-head' },
        React.createElement('div', { style: { flex: 1, minWidth: 0 } },
          React.createElement('h3', null, props.title),
          props.sub ? React.createElement('div', { className: 'sub' }, props.sub) : null),
        props.headExtra || null,
        React.createElement('span', { className: 'drawer-close', onClick: props.onClose },
          React.createElement(Icon, { name: 'close', size: 18 }))),
      React.createElement('div', { className: 'drawer-body' }, props.children),
      props.footer !== null ? React.createElement('div', { className: 'drawer-foot' },
        props.footer || React.createElement(React.Fragment, null,
          React.createElement(Btn, { onClick: props.onClose }, '取消'),
          React.createElement(Btn, { type: 'primary', onClick: props.onOk }, props.okText || '保存'))) : null));
}

/* ---------- Modal ---------- */
function Modal(props) {
  if (!props.open) return null;
  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'overlay', onClick: props.maskClosable === false ? undefined : props.onClose }),
    React.createElement('div', { className: 'modal' + (props.wide ? ' wide' : ''), style: props.style },
      React.createElement('div', { className: 'modal-head' }, React.createElement('h3', null, props.title),
        React.createElement('span', { className: 'drawer-close', style: { marginLeft: 'auto' }, onClick: props.onClose },
          React.createElement(Icon, { name: 'close', size: 18 }))),
      React.createElement('div', { className: 'modal-body' }, props.children),
      props.footer !== null ? React.createElement('div', { className: 'modal-foot' },
        props.footer || React.createElement(React.Fragment, null,
          React.createElement(Btn, { onClick: props.onClose }, '取消'),
          React.createElement(Btn, { type: 'primary', onClick: props.onOk }, props.okText || '确定'))) : null));
}

/* ---------- Dropdown ---------- */
function Dropdown(props) {
  var ref = useRef(); var [open, setOpen] = useState(false);
  useClickOutside(ref, function () { setOpen(false); }, open);
  return React.createElement('div', { className: 'dropdown', ref: ref },
    React.createElement('span', { onClick: function () { setOpen(!open); } }, props.trigger),
    open ? React.createElement('div', { className: 'dropdown-menu' + (props.align === 'left' ? ' left' : '') },
      props.items.map(function (it, i) {
        if (it.divider) return React.createElement('div', { className: 'dd-divider', key: 'd' + i });
        return React.createElement('div', { key: i, className: 'dd-item' + (it.danger ? ' danger' : '') + (it.disabled ? ' disabled' : ''),
          onClick: function () { if (it.disabled) return; setOpen(false); it.onClick && it.onClick(); } },
          it.icon ? React.createElement(Icon, { name: it.icon, size: 15 }) : null, it.label);
      })) : null);
}

/* ---------- Steps (run status) ---------- */
function Steps(props) {
  return React.createElement('div', { className: 'steps' }, props.items.map(function (s, i) {
    var state = i < props.current ? 'done' : i === props.current ? 'active' : '';
    return React.createElement('div', { key: i, className: 'step ' + state },
      React.createElement('span', { className: 'step-icon' }, state === 'done' ? React.createElement(Icon, { name: 'check', size: 14 }) : (i + 1)),
      React.createElement('span', { className: 'step-meta' }, React.createElement('b', null, s.title),
        s.desc ? React.createElement('span', null, s.desc) : null),
      i < props.items.length - 1 ? React.createElement('span', { className: 'step-line' }) : null);
  }));
}

/* ---------- Timeline ---------- */
function Timeline(props) {
  return React.createElement('div', { className: 'timeline' }, props.items.map(function (it, i) {
    return React.createElement('div', { key: i, className: 'tl-item' + (it.gray ? ' gray' : '') },
      React.createElement('span', { className: 'tl-dot' }),
      React.createElement('div', { className: 'tl-time' }, it.time),
      React.createElement('div', { className: 'tl-body' }, it.body));
  }));
}

/* ---------- Diff (old -> new) ---------- */
function Diff(props) {
  return React.createElement('div', { className: 'diff' },
    React.createElement('div', { className: 'diff-col old' },
      React.createElement('div', { className: 'diff-label' }, '原值'),
      props.rows.map(function (r, i) {
        return React.createElement('div', { className: 'diff-row', key: i },
          React.createElement('span', { className: 'k' }, r.k),
          React.createElement('span', { className: 'v old' }, r.old));
      })),
    React.createElement('div', { className: 'diff-arrow' }, React.createElement(Icon, { name: 'arrowRight', size: 18 })),
    React.createElement('div', { className: 'diff-col new' },
      React.createElement('div', { className: 'diff-label' }, '变更后'),
      props.rows.map(function (r, i) {
        return React.createElement('div', { className: 'diff-row', key: i },
          React.createElement('span', { className: 'k' }, r.k),
          React.createElement('span', { className: 'v new' }, r['new']));
      })));
}

/* ---------- Auth wrapper（按钮级权限） ---------- */
var IdentityCtx = createContext(null);
function useIdentity() { return useContext(IdentityCtx); }
function Auth(props) {
  var id = useIdentity();
  var ok = HD.hasPerm(id, props.code);
  if (ok) return props.children;
  if (props.fallback) return props.fallback;
  if (props.disabled) {
    // render children disabled-looking (clone not trivial) — wrap
    return React.createElement('span', { title: '无权限：' + props.code, style: { opacity: .4, pointerEvents: 'none' } }, props.children);
  }
  return null;
}

/* ---------- 审核流双按钮（通过/拒绝 + 原因） ---------- */
function AuditActions(props) {
  return React.createElement('div', { className: 'row-actions' },
    React.createElement(Btn, { type: 'text', size: 'sm', className: 'primary', onClick: props.onApprove }, '通过'),
    React.createElement(Btn, { type: 'text', size: 'sm', danger: true, onClick: props.onReject }, '拒绝'));
}

Object.assign(window, {
  OverlayHost: OverlayHost, Table: Table, Pagination: Pagination,
  EmptyState: EmptyState, LoadingState: LoadingState, ErrorState: ErrorState,
  Drawer: Drawer, Modal: Modal, Dropdown: Dropdown, Steps: Steps, Timeline: Timeline, Diff: Diff,
  IdentityCtx: IdentityCtx, useIdentity: useIdentity, Auth: Auth, AuditActions: AuditActions,
});
