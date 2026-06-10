/* ============================================================
   图片上传 + 预设比例裁剪（轮播图/公告封面共用）
   选图 → 进入裁剪框（可缩放/拖动）→ 按预设宽高比裁剪 → canvas 导出 JPEG Blob
   → POST /a/files/upload 上传，业务表只存返回的 URL。
   ============================================================ */

var GRADS = {
  '#grad1': 'linear-gradient(120deg,#1677ff,#69b1ff)',
  '#grad2': 'linear-gradient(120deg,#52c41a,#95de64)',
  '#grad3': 'linear-gradient(120deg,#fa541c,#ffa940)',
  '#grad4': 'linear-gradient(120deg,#722ed1,#b37feb)',
  '#grad5': 'linear-gradient(120deg,#13a8a8,#5cdbd3)',
  '#grad6': 'linear-gradient(120deg,#eb2f96,#ff85c0)',
};
function gradOf(url) { return GRADS[url] || 'linear-gradient(120deg,#8c8c8c,#bfbfbf)'; }

/* 预设裁剪比例常量（可配置） */
var CROP_PRESETS = {
  banner: { w: 750, h: 307, label: '轮播图 750 × 307（≈2.44 : 1）' },
  cover: { w: 800, h: 450, label: '公告封面 800 × 450（16 : 9）' },
};

/* ---------- 缩略图预览 / 上传位 ---------- */
function ImageField(props) {
  var inputRef = useRef(null);
  var [busy, setBusy] = useState(false);
  var [cropFile, setCropFile] = useState(null);
  var preset = CROP_PRESETS[props.preset] || CROP_PRESETS.banner;
  var ratio = preset.h / preset.w;
  var boxW = props.previewW || 200;
  var dir = props.dir || (props.preset === 'cover' ? 'announcement' : 'banner');
  var isRealUrl = props.value && /^(https?:|\/)/.test(props.value);
  function pick() { if (!busy && inputRef.current) inputRef.current.click(); }
  function onFile(e) {
    var file = e.target.files && e.target.files[0];
    e.target.value = ''; // 允许重选同一文件
    if (file) setCropFile(file); // 进入裁剪
  }
  function onCropped(blob) {
    setCropFile(null);
    setBusy(true);
    var f = new File([blob], 'crop.jpg', { type: 'image/jpeg' }); // 带扩展名，过后端 FileValidator
    API.upload(f, dir).then(function (r) {
      props.onChange && props.onChange(r.url);
      window.message && window.message.success('图片已裁剪并上传');
    }).catch(function () {}).then(function () { setBusy(false); });
  }
  return React.createElement('div', null,
    React.createElement('input', { ref: inputRef, type: 'file', accept: 'image/*', style: { display: 'none' }, onChange: onFile }),
    props.value ?
      React.createElement('div', { className: 'thumb-preview', style: { width: boxW, height: boxW * ratio, background: isRealUrl ? '#f0f0f0' : gradOf(props.value), display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' } },
        isRealUrl ? React.createElement('img', { src: props.value, alt: '', style: { width: '100%', height: '100%', objectFit: 'cover' }, onError: function (e) { e.target.style.display = 'none'; } })
          : React.createElement('span', { style: { color: 'rgba(255,255,255,.9)', fontSize: 12, fontWeight: 600 } }, preset.w + '×' + preset.h),
        React.createElement('div', { className: 'tp-mask' },
          React.createElement('a', { onClick: pick }, React.createElement(Icon, { name: 'crop', size: 18 }), '重裁'),
          React.createElement('a', { onClick: function () { props.onChange && props.onChange(''); } }, React.createElement(Icon, { name: 'trash', size: 18 }), '移除'))) :
      React.createElement('div', { className: 'uploader', style: { width: boxW, padding: '18px 12px' }, onClick: pick },
        React.createElement('div', { className: 'up-icon' }, React.createElement(Icon, { name: 'upload', size: 24 })),
        React.createElement('div', { className: 'up-main' }, busy ? '上传中…' : '点击上传图片'),
        React.createElement('div', { className: 'up-hint' }, '上传后进入裁剪框')),
    React.createElement('div', { className: 'crop-ratio-note', style: { marginTop: 8 } },
      React.createElement(Icon, { name: 'crop', size: 13 }), '预设比例 · ' + preset.label),
    cropFile ? React.createElement(CropModal, { file: cropFile, preset: preset,
      onCancel: function () { setCropFile(null); }, onOk: onCropped }) : null);
}

/* ---------- 裁剪弹窗（真实缩放/拖动 + canvas 导出） ---------- */
function CropModal(props) {
  var preset = props.preset;
  var FRAME_W = 420, FRAME_H = Math.round(FRAME_W * preset.h / preset.w);
  var imgRef = useRef(null);
  var frameRef = useRef(null);
  var dragRef = useRef(null);
  var viewRef = useRef({ nat: null, zoom: 1, off: { x: 0, y: 0 } });
  var [url, setUrl] = useState('');
  var [nat, setNat] = useState(null);          // 原图自然尺寸 {w,h}
  var [zoom, setZoom] = useState(1);
  var [off, setOff] = useState({ x: 0, y: 0 }); // 图片左上角相对裁剪框的偏移(px)
  // 每次渲染同步最新视图状态到 ref，供下面"稳定"事件处理器读取，避免闭包过期
  viewRef.current = { nat: nat, zoom: zoom, off: off };

  useEffect(function () {
    var u = URL.createObjectURL(props.file);
    setUrl(u);
    return function () { URL.revokeObjectURL(u); };
  }, [props.file]);

  function baseScale(n) { return n ? Math.max(FRAME_W / n.w, FRAME_H / n.h) : 1; }
  function clampOff(o, z, n) {
    var s = baseScale(n) * z, dw = n.w * s, dh = n.h * s;
    return { x: Math.min(0, Math.max(FRAME_W - dw, o.x)), y: Math.min(0, Math.max(FRAME_H - dh, o.y)) };
  }
  function zoomTo(z) {
    var v = viewRef.current; if (!v.nat) return;
    z = Math.min(4, Math.max(1, z));
    var sOld = baseScale(v.nat) * v.zoom, sNew = baseScale(v.nat) * z, cx = FRAME_W / 2, cy = FRAME_H / 2;
    var nx = cx - (cx - v.off.x) * (sNew / sOld), ny = cy - (cy - v.off.y) * (sNew / sOld);
    setZoom(z); setOff(clampOff({ x: nx, y: ny }, z, v.nat));
  }
  // 稳定的全局拖拽 + 滚轮缩放监听；unmount（含拖拽中关闭弹窗）时强制清理，防监听残留与过期闭包
  useEffect(function () {
    function onMove(e) {
      var d = dragRef.current; if (!d) return;
      setOff(clampOff({ x: d.ox + (e.clientX - d.sx), y: d.oy + (e.clientY - d.sy) }, d.z, d.nat));
    }
    function onUp() { dragRef.current = null; }
    function onWheel(e) {
      if (!viewRef.current.nat) return;
      e.preventDefault();
      zoomTo(viewRef.current.zoom + (e.deltaY < 0 ? 0.12 : -0.12));
    }
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    var frame = frameRef.current;
    if (frame) frame.addEventListener('wheel', onWheel, { passive: false });
    return function () {
      dragRef.current = null;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      if (frame) frame.removeEventListener('wheel', onWheel);
    };
  }, []);
  function onImgLoad(e) {
    var n = { w: e.target.naturalWidth, h: e.target.naturalHeight };
    var s0 = Math.max(FRAME_W / n.w, FRAME_H / n.h);
    setNat(n); setZoom(1);
    setOff({ x: (FRAME_W - n.w * s0) / 2, y: (FRAME_H - n.h * s0) / 2 }); // 居中
  }
  function onDown(e) {
    if (!nat) return; // 图片未加载前不响应拖动
    e.preventDefault();
    dragRef.current = { sx: e.clientX, sy: e.clientY, ox: off.x, oy: off.y, z: zoom, nat: nat };
  }

  function confirm() {
    if (!nat || !imgRef.current) return;
    var s = baseScale(nat) * zoom;
    var sx = -off.x / s, sy = -off.y / s, sw = FRAME_W / s, sh = FRAME_H / s;
    var canvas = document.createElement('canvas');
    canvas.width = preset.w; canvas.height = preset.h;
    canvas.getContext('2d').drawImage(imgRef.current, sx, sy, sw, sh, 0, 0, preset.w, preset.h);
    canvas.toBlob(function (blob) { if (blob) props.onOk(blob); }, 'image/jpeg', 0.9);
  }

  var dispW = nat ? nat.w * baseScale(nat) * zoom : 0, dispH = nat ? nat.h * baseScale(nat) * zoom : 0;
  return React.createElement(React.Fragment, null,
    React.createElement('div', { className: 'overlay' }),
    React.createElement('div', { className: 'modal wide', style: { width: 520 } },
      React.createElement('div', { className: 'modal-head' },
        React.createElement('h3', null, '裁剪图片'),
        React.createElement('span', { className: 'drawer-close', style: { marginLeft: 'auto' }, onClick: props.onCancel },
          React.createElement(Icon, { name: 'close', size: 18 }))),
      React.createElement('div', { className: 'modal-body' },
        React.createElement('div', { className: 'crop-ratio-note', style: { marginBottom: 12 } },
          React.createElement(Icon, { name: 'info', size: 13 }), '按 ' + preset.label + ' 裁剪 · 滚轮/滑块缩放，拖动调整位置'),
        React.createElement('div', { style: { display: 'flex', justifyContent: 'center' } },
          React.createElement('div', { ref: frameRef, className: 'crop-frame', style: { width: FRAME_W, height: FRAME_H, position: 'relative', overflow: 'hidden', background: '#222', cursor: 'move', userSelect: 'none' }, onMouseDown: onDown },
            url ? React.createElement('img', { ref: imgRef, src: url, alt: '', onLoad: onImgLoad, draggable: false,
              style: { position: 'absolute', left: off.x, top: off.y, width: dispW || 'auto', height: dispH || 'auto', maxWidth: 'none', pointerEvents: 'none' } }) : null,
            React.createElement('div', { className: 'grid-l', style: { left: '33.33%', top: 0, bottom: 0, width: 1 } }),
            React.createElement('div', { className: 'grid-l', style: { left: '66.66%', top: 0, bottom: 0, width: 1 } }),
            React.createElement('div', { className: 'grid-l', style: { top: '33.33%', left: 0, right: 0, height: 1 } }),
            React.createElement('div', { className: 'grid-l', style: { top: '66.66%', left: 0, right: 0, height: 1 } }))),
        React.createElement('div', { style: { display: 'flex', alignItems: 'center', gap: 12, marginTop: 14, color: 'var(--text-3)', fontSize: 13 } },
          React.createElement(Icon, { name: 'search', size: 14 }),
          React.createElement('input', { type: 'range', min: 1, max: 4, step: 0.01, value: zoom, onChange: function (e) { zoomTo(Number(e.target.value)); }, style: { flex: 1 } }),
          '缩放 ' + zoom.toFixed(2) + '×')),
      React.createElement('div', { className: 'modal-foot' },
        React.createElement(Btn, { onClick: props.onCancel }, '取消'),
        React.createElement(Btn, { type: 'primary', icon: 'crop', onClick: confirm, disabled: !nat }, '确认裁剪并上传'))));
}

/* ---------- 通用文件拖拽上传位（活动总结图 / Excel 导入 / 文件下载） ----------
   传 onFile(file) 则内置真实文件选择；否则回退到 props.onClick（保持旧用法兼容）。 */
function DropUpload(props) {
  var inputRef = useRef(null);
  function onChange(e) { var file = e.target.files && e.target.files[0]; e.target.value = ''; if (file && props.onFile) props.onFile(file); }
  function handleClick() { if (props.onFile) { inputRef.current && inputRef.current.click(); } else if (props.onClick) { props.onClick(); } }
  return React.createElement('div', { className: 'uploader', onClick: handleClick },
    props.onFile ? React.createElement('input', { ref: inputRef, type: 'file', accept: props.accept, style: { display: 'none' }, onChange: onChange }) : null,
    React.createElement('div', { className: 'up-icon' }, React.createElement(Icon, { name: props.icon || 'upload', size: 28 })),
    React.createElement('div', { className: 'up-main' }, props.main || '点击或拖拽文件到此处'),
    React.createElement('div', { className: 'up-hint' }, props.hint || '支持单个文件上传'));
}

Object.assign(window, { ImageField: ImageField, CropModal: CropModal, DropUpload: DropUpload, gradOf: gradOf, CROP_PRESETS: CROP_PRESETS });
