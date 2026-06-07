const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

Page({
  data: {
    activityId: "",
    activity: {},
    records: []
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
        records: activity.records || activity.volunteers || []
      });
    } catch (error) {
      wx.showToast({ title: error.message === "HTTP 403" ? "暂无权限" : "活动签到接口暂不可用", icon: "none" });
      wx.redirectTo({ url: "/pages/activity-leader/index" });
    }
  },

  goBack() {
    wx.redirectTo({ url: `/pages/activity-leader/detail?id=${this.data.activityId}` });
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

  openSignRecords() {
    wx.redirectTo({ url: `/pages/activity-leader/sign-records?id=${this.data.activity.id}` });
  },

  async confirmAttendance(event) {
    const volunteerId = event.currentTarget.dataset.id;
    if (!volunteerId) return;
    try {
      await dataService.updateManagedAttendance(this.data.activity.id, volunteerId, {
        attendStatus: 1,
        checkIn: true
      });
      wx.showToast({ title: "已确认签到", icon: "none" });
      this.loadDetail();
    } catch (error) {
      wx.showToast({ title: "确认失败", icon: "none" });
    }
  },

  async checkOutVolunteer(event) {
    const volunteerId = event.currentTarget.dataset.id;
    if (!volunteerId) return;
    try {
      await dataService.checkOutManagedActivity(this.data.activity.id, {
        volunteerIds: [Number(volunteerId)]
      });
      wx.showToast({ title: "已签退", icon: "none" });
      this.loadDetail();
    } catch (error) {
      wx.showToast({ title: "签退失败", icon: "none" });
    }
  },

  emergencyReport() {
    wx.makePhoneCall({ phoneNumber: this.data.activity.phone || "15766508094" });
  },

  openViolation() {
    wx.redirectTo({ url: `/pages/activity-leader/violation?id=${this.data.activity.id}` });
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
