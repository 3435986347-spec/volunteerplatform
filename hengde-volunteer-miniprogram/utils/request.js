const auth = require("./auth");

function getBaseUrl() {
  const app = getApp();
  return app.globalData.apiBaseUrl || "http://localhost:8080/api";
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
        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error(`HTTP ${response.statusCode}`));
          return;
        }
        if (body && typeof body === "object" && body.code !== undefined) {
          const code = Number(body.code);
          if (![0, 200].includes(code)) {
            reject(new Error(body.message || body.msg || `接口返回 code=${body.code}`));
            return;
          }
        }
        resolve(body);
      },
      fail(error) {
        reject(error);
      }
    });
  });
}

module.exports = {
  request
};
