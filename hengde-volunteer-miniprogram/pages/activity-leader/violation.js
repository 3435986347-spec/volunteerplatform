const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

const REASONS = ["请假", "迟到", "缺席", "玩手机", "服装不合格", "早退", "长时间交头接耳", "穿拖鞋"];

Page({
  data: {
    activityId: "",
    activity: {},
    volunteers: [],
    details: [],
    reasons: REASONS,
    selectedVolunteer: {},
    selectedReason: "",
    showRecordModal: false
  },

  async onLoad(query = {}) {
    if (!auth.requireLogin()) return;
    this.setData({ activityId: query.id || "" });
    this.loadDetail();
  },

  async loadDetail() {
    try {
      const activity = await dataService.getManagedActivity(this.data.activityId);
      this.setData({
        activity,
        volunteers: activity.volunteers || [],
        details: activity.details || []
      });
    } catch (error) {
      wx.showToast({ title: error.message === "HTTP 403" ? "暂无权限" : "违规记录接口暂不可用", icon: "none" });
      wx.redirectTo({ url: "/pages/activity-leader/index" });
    }
  },

  goBack() {
    wx.redirectTo({ url: `/pages/activity-leader/detail?id=${this.data.activityId}` });
  },

  openRecord(event) {
    const index = Number(event.currentTarget.dataset.index);
    this.setData({
      selectedVolunteer: this.data.volunteers[index] || {},
      selectedReason: "",
      showRecordModal: true
    });
  },

  closeRecordModal() {
    this.setData({ showRecordModal: false });
  },

  chooseReason(event) {
    this.setData({ selectedReason: this.data.reasons[Number(event.detail.value)] });
  },

  async confirmRecord() {
    const volunteer = this.data.selectedVolunteer || {};
    if (!volunteer.id || !this.data.selectedReason) {
      wx.showToast({ title: "请选择", icon: "none" });
      return;
    }
    try {
      await dataService.recordManagedViolation(this.data.activity.id, volunteer.id, {
        description: this.data.selectedReason,
        violationType: 0
      });
      this.setData({ showRecordModal: false });
      wx.showToast({ title: "记录", icon: "none" });
      this.loadDetail();
    } catch (error) {
      wx.showToast({ title: "记录失败", icon: "none" });
    }
  },

  openActivityDetail() {
    wx.navigateTo({ url: `/pages/activity/detail?id=${this.data.activity.id}&fromLeader=1` });
  },

  async startActivity() {
    try {
      await dataService.startManagedActivity(this.data.activity.id);
      wx.showToast({ title: "开始活动", icon: "none" });
      this.loadDetail();
    } catch (error) {
      wx.showToast({ title: "开始活动失败", icon: "none" });
    }
  },

  async finishActivity() {
    try {
      await dataService.finishManagedActivity(this.data.activity.id);
      wx.showToast({ title: "结束活动", icon: "none" });
      this.loadDetail();
    } catch (error) {
      wx.showToast({ title: "结束活动失败", icon: "none" });
    }
  },

  openSign() {
    wx.redirectTo({ url: `/pages/activity-leader/sign?id=${this.data.activity.id}` });
  },

  emergencyReport() {
    wx.makePhoneCall({ phoneNumber: this.data.activity.phone || "15766508094" });
  },

  uploadPhoto() {
    wx.setStorageSync("activityLeaderReturnDetail", "1");
    wx.setStorageSync("activityLeaderReturnId", this.data.activity.id);
    wx.redirectTo({ url: "/pages/mine/my-activities?mode=upload&fromLeader=1" });
  },

  evaluateActivity() {
    wx.setStorageSync("activityLeaderReturnDetail", "1");
    wx.setStorageSync("activityLeaderReturnId", this.data.activity.id);
    wx.redirectTo({ url: "/pages/mine/my-activities?mode=evaluate&fromLeader=1" });
  }
});
