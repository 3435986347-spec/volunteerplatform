const AUTH_KEY = "hengdeVolunteerLoggedIn";
const TOKEN_KEY = "hengdeVolunteerToken";
const ROLE_KEY = "hengdeVolunteerRole";
const USER_KEY = "hengdeVolunteerUser";
const PERMISSIONS_KEY = "hengdeVolunteerPermissions";

function isLoggedIn() {
  return wx.getStorageSync(AUTH_KEY) === "1";
}

function setLoggedIn(options = {}) {
  wx.setStorageSync(AUTH_KEY, "1");
  if (Object.prototype.hasOwnProperty.call(options, "token")) {
    if (!options.token) {
      wx.removeStorageSync(TOKEN_KEY);
    } else {
      wx.setStorageSync(TOKEN_KEY, options.token);
    }
  }
  if (options.role) {
    wx.setStorageSync(ROLE_KEY, options.role);
  }
  const user = {};
  ["registered", "phone", "nickName", "nickname", "realName", "avatarUrl"].forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(options, key)) {
      user[key === "nickname" ? "nickName" : key] = options[key];
    }
  });
  if (Object.keys(user).length) {
    wx.setStorageSync(USER_KEY, Object.assign({}, getUser(), user));
  }
  if (Array.isArray(options.permissions)) {
    setPermissions(options.permissions);
  }
}

function clearLogin() {
  wx.removeStorageSync(AUTH_KEY);
  wx.removeStorageSync(TOKEN_KEY);
  wx.removeStorageSync(ROLE_KEY);
  wx.removeStorageSync(USER_KEY);
  wx.removeStorageSync(PERMISSIONS_KEY);
}

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || "";
}

function getRole() {
  return wx.getStorageSync(ROLE_KEY) || "volunteer";
}

function getUser() {
  return wx.getStorageSync(USER_KEY) || {};
}

function setPermissions(permissions = []) {
  const list = Array.isArray(permissions)
    ? permissions.map((item) => String(item)).filter(Boolean)
    : [];
  wx.setStorageSync(PERMISSIONS_KEY, list);
}

function getPermissions() {
  const permissions = wx.getStorageSync(PERMISSIONS_KEY);
  return Array.isArray(permissions) ? permissions : [];
}

function hasPermission(code) {
  return getPermissions().includes(code);
}

function hasAnyPermission(codes = []) {
  return codes.some((code) => hasPermission(code));
}

function requireLogin() {
  if (isLoggedIn()) {
    return true;
  }

  wx.reLaunch({
    url: "/pages/login/index"
  });
  return false;
}

module.exports = {
  isLoggedIn,
  setLoggedIn,
  clearLogin,
  getToken,
  getRole,
  getUser,
  setPermissions,
  getPermissions,
  hasPermission,
  hasAnyPermission,
  requireLogin
};
