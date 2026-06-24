const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");
const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");

function pickToken(result) {
  if (typeof result === "string") {
    return result;
  }
  if (typeof result?.data === "string") {
    return result.data;
  }
  return result?.token ||
    result?.tokenValue ||
    result?.data?.token ||
    result?.data?.tokenValue ||
    result?.data?.accessToken ||
    result?.accessToken ||
    "";
}

function pickRegistered(result, fallback = false) {
  if (result?.registered !== undefined) return Boolean(result.registered);
  if (result?.data?.registered !== undefined) return Boolean(result.data.registered);
  return fallback;
}

function pickLoginUser(result) {
  const data = result?.data && typeof result.data === "object" ? result.data : result || {};
  return {
    registered: pickRegistered(result, false),
    phone: data.phone || data.mobile || "",
    nickName: data.nickName || data.nickname || data.wxNickName || "",
    realName: data.realName || "",
    avatarUrl: data.avatarUrl || data.avatar || ""
  };
}

function pickPermissions(result) {
  const data = result?.data !== undefined ? result.data : result;
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.permissions)) return data.permissions;
  if (Array.isArray(data?.permissionCodes)) return data.permissionCodes;
  if (Array.isArray(data?.codes)) return data.codes;
  return [];
}

Page({
  data: {
    loginMode: "sms",
    agreed: false,
    account: "",
    password: "",
    phone: "",
    smsCode: "",
    captchaCode: "",
    submitting: false,
    submittingTarget: "",
    icons: {
      hero: "/assets/login/leizhou-hero-transparent.png",
      back: "/assets/login/back-transparent.png",
      account: "/assets/login/account.png",
      password: "/assets/login/password.png",
      shield: "/assets/login/captcha-shield.png",
      phone: "/assets/login/phone.png",
      message: "/assets/login/sms-message.png",
      captcha: "/assets/login/captcha-code.png"
    }
  },

  onLoad(query = {}) {
    if (query.switch === "1") {
      auth.clearLogin();
      return;
    }

    if (auth.isLoggedIn()) {
      wx.switchTab({ url: "/pages/home/index" });
    }
  },

  switchMode(event) {
    this.setData({ loginMode: event.currentTarget.dataset.mode });
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) {
      return;
    }
    this.setData({
      [field]: event.detail.value
    });
  },

  toggleAgreement() {
    this.setData({ agreed: !this.data.agreed });
  },

  // 登录页发码：场景 login（与注册 register 隔离）。失败直接显示后端原因，不再本地伪造验证码。
  async getSmsCode() {
    if (!this.data.phone) {
      wx.showToast({ title: "请输入手机号", icon: "none" });
      return;
    }
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.smsCodes,
        method: "POST",
        data: {
          phone: this.data.phone,
          scene: "login"
        }
      });
      wx.showToast({ title: "验证码已发送", icon: "none" });
    } catch (error) {
      wx.showToast({ title: error.message || "验证码发送失败", icon: "none" });
    }
  },

  async login() {
    if (!this.data.agreed) {
      wx.showToast({
        title: "请先同意系统许可及服务协议",
        icon: "none"
      });
      return;
    }
    if (!this.validateLoginInput()) {
      return;
    }

    this.setData({ submitting: true, submittingTarget: "volunteer" });

    try {
      await this.loginVolunteer();
      await this.loadMyPermissions();
      wx.switchTab({ url: "/pages/home/index" });
    } catch (error) {
      // 真实登录失败（密码错/验证码错/账号禁用）直接显示后端原因，绝不回退到免鉴权 dev 登录
      wx.showToast({
        title: error.message || "登录失败，请检查账号或验证码",
        icon: "none"
      });
      auth.clearLogin();
    } finally {
      this.setData({ submitting: false, submittingTarget: "" });
    }
  },

  validateLoginInput() {
    if (this.data.loginMode === "account") {
      if (!this.data.account) {
        wx.showToast({ title: "请输入手机号", icon: "none" });
        return false;
      }
      if (!this.data.password) {
        wx.showToast({ title: "请输入密码", icon: "none" });
        return false;
      }
    } else {
      if (!this.data.phone) {
        wx.showToast({ title: "请输入手机号", icon: "none" });
        return false;
      }
      if (!this.data.smsCode) {
        wx.showToast({ title: "请输入验证码", icon: "none" });
        return false;
      }
    }
    return true;
  },

  async loadMyPermissions() {
    try {
      const permissions = await dataService.loadMyPermissions();
      auth.setPermissions(permissions);
    } catch (error) {
      try {
        const profile = await request({ url: ENDPOINTS.volunteer.user.profile });
        auth.setPermissions(pickPermissions(profile));
      } catch (profileError) {
        if (!auth.getPermissions().length) {
          auth.setPermissions([]);
        }
      }
    }
  },

  async loginVolunteer() {
    // dev 登录仅当本地显式开启 devVolunteerLogin 时走（联调用），不在失败路径兜底
    if (this.shouldUseDevVolunteerLogin()) {
      await this.loginVolunteerDev();
      return;
    }

    // 按登录方式分流：账号登录=手机号+密码；短信登录=手机号+验证码
    const isAccount = this.data.loginMode === "account";
    const url = isAccount
      ? ENDPOINTS.volunteer.auth.passwordLogin
      : ENDPOINTS.volunteer.auth.smsLogin;
    const data = isAccount
      ? { phone: this.data.account, password: this.data.password }
      : { phone: this.data.phone, smsCode: this.data.smsCode };

    const result = await request({ url, method: "POST", data });

    auth.setLoggedIn({
      token: pickToken(result),
      role: "volunteer",
      ...pickLoginUser(result),
      permissions: pickPermissions(result),
      phone: pickLoginUser(result).phone || data.phone
    });
    if (!auth.getToken()) {
      throw new Error("登录成功但未返回 token");
    }
  },

  shouldUseDevVolunteerLogin() {
    const app = getApp();
    return Boolean(app?.globalData?.devVolunteerLogin);
  },

  async loginVolunteerDev() {
    const result = await request({
      url: ENDPOINTS.volunteer.auth.devLogin,
      method: "POST",
      data: {
        key: this.data.phone || this.data.account || "tester",
        registered: true
      }
    });

    auth.setLoggedIn({
      token: pickToken(result),
      role: "volunteer",
      ...pickLoginUser(result),
      permissions: pickPermissions(result),
      phone: pickLoginUser(result).phone || this.data.phone
    });
    if (!auth.getToken()) {
      throw new Error("开发登录成功但未返回 token");
    }
  },

  getWxLoginCode() {
    return new Promise((resolve, reject) => {
      wx.login({
        success(result) {
          if (result.code) {
            resolve(result.code);
            return;
          }
          reject(new Error("wx.login failed"));
        },
        fail: reject
      });
    });
  },

  quickLogin() {
    this.login();
  },

  enterpriseLogin() {
    wx.showToast({
      title: "企业登录为后续版本预留",
      icon: "none"
    });
  },

  goForgotPassword() {
    wx.navigateTo({ url: "/pages/login/forgot" });
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
    }
  }
});
