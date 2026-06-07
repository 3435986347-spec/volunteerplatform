const LOCAL_API_PATTERN = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/api\/?$/;
const LOCAL_SMS_CODE = "123456";

function getBaseUrl() {
  const app = getApp();
  return app.globalData.apiBaseUrl || "";
}

function isEnabled() {
  return LOCAL_API_PATTERN.test(getBaseUrl());
}

function createSession(options = {}) {
  if (!isEnabled()) {
    return null;
  }

  const role = options.role || (options.mode === "account" ? "admin" : "volunteer");
  return {
    token: `local-${role}-token-${Date.now()}`,
    role,
    registered: role !== "volunteer" || Boolean(options.registered),
    phone: options.phone || "",
    nickName: options.account || options.phone || "微信昵称",
    realName: role === "admin" ? (options.account || "管理团队") : ""
  };
}

function createSmsCode() {
  return isEnabled() ? LOCAL_SMS_CODE : "";
}

module.exports = {
  isEnabled,
  createSession,
  createSmsCode
};
