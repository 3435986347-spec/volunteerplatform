const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

const STATUS_TEXT = { 0: "待审核", 1: "已通过", 2: "已驳回" };

Page({
  data: {
    submitting: false,
    reason: "",
    experience: "",
    expectDepartment: "",
    applicant: { name: "", phone: "", school: "" },
    myApp: null,
    statusText: "",
    // 无申请、或上次被驳回(2) 时可重新提交；待审(0)/已通过(1) 时隐藏表单
    canApply: true
  },

  onLoad() {
    if (!auth.requireLogin()) return;
    this.loadApplicant();
    this.loadMyApplication();
  },

  goBack() {
    wx.navigateBack();
  },

  async loadApplicant() {
    try {
      const profile = await dataService.getUserProfile();
      const info = (profile && profile.info) || {};
      this.setData({ applicant: { name: info.name || "", phone: info.phone || "", school: info.school || "" } });
    } catch (error) {
      // 取不到资料保留空，不阻塞
    }
  },

  async loadMyApplication() {
    try {
      const myApp = await dataService.getMyManagerApplication();
      this.setData({
        myApp: myApp || null,
        statusText: myApp ? (STATUS_TEXT[myApp.status] || "") : "",
        canApply: !myApp || myApp.status === 2
      });
    } catch (error) {
      this.setData({ myApp: null, canApply: true });
    }
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: event.detail.value });
  },

  async submit() {
    const reason = (this.data.reason || "").trim();
    if (!reason) {
      wx.showToast({ title: "请填写申请理由", icon: "none" });
      return;
    }
    this.setData({ submitting: true });
    try {
      await dataService.submitManagerApplication({
        reason,
        experience: (this.data.experience || "").trim(),
        expectDepartment: (this.data.expectDepartment || "").trim()
      });
      wx.showToast({ title: "申请已提交", icon: "success" });
      this.setData({ reason: "", experience: "", expectDepartment: "" });
      this.loadMyApplication();
    } catch (error) {
      wx.showToast({ title: error.message || "提交失败", icon: "none" });
    } finally {
      this.setData({ submitting: false });
    }
  }
});
