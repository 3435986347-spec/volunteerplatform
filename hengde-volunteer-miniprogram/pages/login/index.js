const auth = require("../../utils/auth");
const localAuthMock = require("../../utils/local-auth-mock");
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

  async mockGetCode() {
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.smsCodes,
        method: "POST",
        data: {
          phone: this.data.phone,
          scene: "register"
        }
      });
      wx.showToast({
        title: "验证码已发送",
        icon: "none"
      });
    } catch (error) {
      const localCode = localAuthMock.createSmsCode();
      if (localCode) {
        this.setData({ smsCode: localCode });
        wx.showToast({
          title: `Local code: ${localCode}`,
          icon: "none"
        });
        return;
      }

      wx.showToast({
        title: `验证码接口未通：${ENDPOINTS.volunteer.auth.smsCodes}`,
        icon: "none"
      });
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

    this.setData({ submitting: true, submittingTarget: "volunteer" });

    try {
      await this.loginVolunteer();
      await this.loadMyPermissions();
    } catch (error) {
      if (this.loginLocalDebug()) {
        wx.showToast({
          title: "Local debug login",
          icon: "none"
        });
      } else {
        wx.showToast({
          title: error.message || "登录失败，请检查账号或验证码",
          icon: "none"
        });
        auth.setLoggedIn({
          token: "",
          role: "volunteer"
        });
        auth.clearLogin();
        return;
      }
    } finally {
      this.setData({ submitting: false, submittingTarget: "" });
    }

    wx.switchTab({ url: "/pages/home/index" });
  },

  loginLocalDebug() {
    const session = localAuthMock.createSession({
      mode: this.data.loginMode,
      role: "volunteer",
      account: this.data.account,
      phone: this.data.phone,
      registered: true
    });

    if (!session) {
      return false;
    }

    auth.setLoggedIn(session);
    return Boolean(auth.getToken());
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
    if (this.shouldUseDevVolunteerLogin()) {
      await this.loginVolunteerDev();
      return;
    }

    const wxCode = await this.getWxLoginCode();
    const result = await request({
      url: ENDPOINTS.volunteer.auth.wechatLogin,
      method: "POST",
      data: {
        code: wxCode,
        phone: this.data.phone,
        smsCode: this.data.smsCode
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
