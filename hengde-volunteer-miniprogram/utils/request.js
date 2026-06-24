const auth = require("./auth");

function getBaseUrl() {
  const app = getApp();
  return app.globalData.apiBaseUrl || "http://localhost:8080/api";
}

// 后端约定：业务错误时 HTTP 状态是 400/401/403/500，但响应体始终是 {code, message, data}，
// 真正给用户看的原因在 message 里。所以报错时「先取响应体 message，没有再按 HTTP 状态兜底」，
// 避免只抛出一句 "HTTP 400" 让人不知道哪里做错了。
function friendlyByStatus(statusCode) {
  if (statusCode === 401) return "登录已失效，请重新登录";
  if (statusCode === 403) return "无权限进行该操作";
  if (statusCode === 404) return "接口不存在，请检查后端版本";
  if (statusCode >= 500) return "服务器开小差了，请稍后再试";
  if (statusCode >= 400) return `请求有误（${statusCode}）`;
  return "请求失败，请稍后再试";
}

// 统一构造一个带 message/code/statusCode 的错误对象，页面 catch 时直接用 error.message 弹 toast。
function buildError(body, statusCode) {
  const msg =
    (body && typeof body === "object" && (body.message || body.msg)) ||
    friendlyByStatus(statusCode);
  const error = new Error(msg);
  error.statusCode = statusCode;
  if (body && typeof body === "object" && body.code !== undefined) {
    error.code = Number(body.code);
  }
  return error;
}

function request(options) {
  const token = auth.getToken();
  const header = Object.assign(
    {
      "content-type": "application/json"
    },
    options.header || {}
  );

  if (token) {
    header.Authorization = token;
  }

  return new Promise((resolve, reject) => {
    wx.request({
      url: `${getBaseUrl()}${options.url}`,
      method: options.method || "GET",
      data: options.data || {},
      header,
      success(response) {
        const body = response.data;
        const status = response.statusCode;
        const ok2xx = status >= 200 && status < 300;
        const wrapped = body && typeof body === "object" && body.code !== undefined;

        // 业务包装层 {code, message, data}：code=0/200 且 HTTP 2xx 才算成功，否则抛 message
        if (wrapped) {
          const code = Number(body.code);
          if (ok2xx && [0, 200].includes(code)) {
            resolve(body);
          } else {
            reject(buildError(body, status));
          }
          return;
        }

        // 非包装层（极少数原始响应/代理错误页）：按 HTTP 状态判定
        if (ok2xx) {
          resolve(body);
        } else {
          reject(buildError(body, status));
        }
      },
      fail(error) {
        // wx.request 在连不上服务器（后端没启动 / 域名未配 / 断网）时才走这里，
        // errMsg 形如 "request:fail"，对用户没意义，换成可操作的提示。
        const err = new Error("网络连接失败，请检查网络或后端服务是否已启动");
        err.raw = error;
        reject(err);
      }
    });
  });
}

module.exports = {
  request
};
