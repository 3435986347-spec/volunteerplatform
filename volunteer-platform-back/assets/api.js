/* ============================================================
   恒德后台 · 真实接口封装（联调用）
   统一处理：/api 前缀、Sa-Token、响应体 {code,msg,data}、分页、错误提示。
   用法：把页面里的 HD.* mock 换成 await API.get('/a/...').
   ============================================================ */
(function (w) {
  /* ---------- 配置 ---------- */
  // 后端 context-path = /api。三种部署对应不同 BASE：
  //  A. 前端与后端同源（前端打包进 Spring static）：BASE = '/api'
  //  B. 本地分离 + 后端开了 CORS：BASE = 'http://localhost:8080/api'
  //  C. 本地分离 + 用反向代理(nginx)：BASE = '/api'，由代理转发到后端
  var API_BASE = w.__API_BASE__ || 'http://localhost:8080/api';

  var TOKEN_KEY = 'hd_admin_token';
  function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }
  function setToken(t) { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); }

  /* ---------- 核心请求 ---------- */
  function buildUrl(path, query) {
    var url = API_BASE + path;
    if (query) {
      var qs = Object.keys(query)
        .filter(function (k) { return query[k] !== undefined && query[k] !== null && query[k] !== ''; })
        .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(query[k]); })
        .join('&');
      if (qs) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs;
    }
    return url;
  }

  async function request(method, path, opts) {
    opts = opts || {};
    var headers = Object.assign({}, opts.headers);
    var token = getToken();
    if (token) headers['Authorization'] = token;          // Sa-Token：默认读 Authorization 头

    var init = { method: method, headers: headers };
    if (opts.body !== undefined) {
      if (opts.body instanceof FormData) {
        init.body = opts.body;                            // 上传：不要手动设 Content-Type
      } else {
        headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(opts.body);
      }
    }

    var resp;
    try {
      resp = await fetch(buildUrl(path, opts.query), init);
    } catch (e) {
      w.message && w.message.error('网络错误，请检查后端是否启动 / CORS');
      throw e;
    }

    // 401/403：登录态失效
    if (resp.status === 401) {
      setToken('');
      w.message && w.message.error('登录已失效，请重新登录');
      w.dispatchEvent(new CustomEvent('hd:unauthorized'));
      throw new Error('unauthorized');
    }

    var json;
    try { json = await resp.json(); }
    catch (e) { w.message && w.message.error('响应解析失败'); throw e; }

    // 统一响应体：{ code, msg, data }（code===200 视为成功，按你后端实际调整）
    if (json.code !== undefined) {
      if (json.code === 200 || json.code === 0) return json.data;
      var emsg = json.message || json.msg;
      w.message && w.message.error(emsg || ('请求失败（' + json.code + '）'));
      throw new Error(emsg || ('code ' + json.code));
    }
    // 没有包装层就直接返回
    if (!resp.ok) { w.message && w.message.error('请求失败（HTTP ' + resp.status + '）'); throw new Error('http ' + resp.status); }
    return json;
  }

  /* ---------- 便捷方法 ---------- */
  var API = {
    base: API_BASE,
    getToken: getToken, setToken: setToken,
    get: function (path, query) { return request('GET', path, { query: query }); },
    post: function (path, body, query) { return request('POST', path, { body: body, query: query }); },
    put: function (path, body) { return request('PUT', path, { body: body }); },
    patch: function (path, body) { return request('PATCH', path, { body: body }); },
    del: function (path, body) { return request('DELETE', path, { body: body }); },

    // 登录：拿 token 存起来
    login: async function (account, password) {
      var data = await request('POST', '/a/auth/login', { body: { username: account, password: password } });
      // 后端通常返回 { tokenName, tokenValue } 或直接 token 字符串，按实际取
      var token = data && (data.tokenValue || data.token || data.satoken) || data;
      setToken(token);
      return data;
    },
    me: function () { return request('GET', '/a/auth/me'); },

    // 通用上传：multipart file + dir，返回 { url, name, size }
    upload: function (file, dir) {
      var fd = new FormData();
      fd.append('file', file);
      if (dir) fd.append('dir', dir);
      return request('POST', '/a/files/upload', { body: fd });
    },
  };

  w.API = API;
})(window);
