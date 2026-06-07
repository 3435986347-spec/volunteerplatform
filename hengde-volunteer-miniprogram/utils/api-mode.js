const DEFAULT_USE_MOCK_API = true;
const STORAGE_KEY = "hengdeUseMockApi";

function readStoredMode() {
  if (typeof wx === "undefined" || !wx.getStorageSync) {
    return null;
  }
  try {
    const stored = wx.getStorageSync(STORAGE_KEY);
    return typeof stored === "boolean" ? stored : null;
  } catch (error) {
    return null;
  }
}

function useMockApi() {
  const app = typeof getApp === "function" ? getApp() : null;
  if (app && app.globalData && typeof app.globalData.useMockApi === "boolean") {
    return app.globalData.useMockApi;
  }

  const stored = readStoredMode();
  if (stored !== null) {
    return stored;
  }

  return DEFAULT_USE_MOCK_API;
}

function setUseMockApi(value) {
  const enabled = Boolean(value);
  const app = typeof getApp === "function" ? getApp() : null;
  if (app && app.globalData) {
    app.globalData.useMockApi = enabled;
  }
  try {
    if (typeof wx !== "undefined" && wx.setStorageSync) {
      wx.setStorageSync(STORAGE_KEY, enabled);
    }
  } catch (error) {
    // Storage is only a convenience for local switching.
  }
  return enabled;
}

module.exports = {
  useMockApi,
  setUseMockApi
};
