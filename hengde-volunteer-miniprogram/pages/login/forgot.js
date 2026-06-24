const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");

Page({
  data: {
    icons: {
      back: "/assets/login/back-transparent.png"
    },
    phone: "",
    smsCode: "",
    newPassword: "",
    sending: false,
    submitting: false
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: event.detail.value });
  },

  // 发重置验证码：场景 volunteer-password-reset（与登录/注册码隔离）
  async getSmsCode() {
    if (!/^1\d{10}$/.test(this.data.phone)) {
      wx.showToast({ title: "请输入正确手机号", icon: "none" });
      return;
    }
    this.setData({ sending: true });
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.smsCodes,
        method: "POST",
        data: { phone: this.data.phone, scene: "volunteer-password-reset" }
      });
      wx.showToast({ title: "验证码已发送", icon: "none" });
    } catch (error) {
      wx.showToast({ title: error.message || "验证码发送失败", icon: "none" });
    } finally {
      this.setData({ sending: false });
    }
  },

  // 手机号+验证码+新密码 重置登录密码
  async confirm() {
    if (!/^1\d{10}$/.test(this.data.phone)) {
      wx.showToast({ title: "请输入正确手机号", icon: "none" });
      return;
    }
    if (!this.data.smsCode) {
      wx.showToast({ title: "请输入验证码", icon: "none" });
      return;
    }
    const newPassword = (this.data.newPassword || "").trim();
    if (newPassword.length < 6 || newPassword.length > 32) {
      wx.showToast({ title: "新密码需 6-32 位", icon: "none" });
      return;
    }
    this.setData({ submitting: true });
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.passwordReset,
        method: "PUT",
        data: { phone: this.data.phone, smsCode: this.data.smsCode, newPassword }
      });
      wx.showToast({ title: "密码已重置，请登录", icon: "success" });
      setTimeout(() => wx.navigateBack(), 700);
    } catch (error) {
      wx.showToast({ title: error.message || "重置失败", icon: "none" });
    } finally {
      this.setData({ submitting: false });
    }
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.navigateTo({ url: "/pages/login/index?switch=1" });
  }
});
