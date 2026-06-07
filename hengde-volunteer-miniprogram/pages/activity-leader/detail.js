const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

function returnLeaderIndex() {
  wx.redirectTo({ url: "/pages/activity-leader/index" });
}

Page({
  data: {
    activityId: "",
    activity: {},
    volunteers: [],
    started: false
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
        started: Boolean(activity.startTime)
      });
    } catch (error) {
      wx.showToast({ title: error.message === "HTTP 403" ? "暂无权限" : "活动详情接口暂不可用", icon: "none" });
      returnLeaderIndex();
    }
  },

  goBack() {
    returnLeaderIndex();
  },

  openActivityDetail() {
    wx.navigateTo({ url: `/pages/activity/detail?id=${this.data.activity.id}&fromLeader=1` });
  },

  async startActivity() {
    try {
      await dataService.startManagedActivity(this.data.activity.id);
      this.setData({ started: true });
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
    wx.navigateTo({ url: `/pages/activity-leader/sign?id=${this.data.activity.id}` });
  },

  emergencyReport() {
    wx.makePhoneCall({ phoneNumber: this.data.activity.phone || "15766508094" });
  },

  openViolation() {
    wx.navigateTo({ url: `/pages/activity-leader/violation?id=${this.data.activity.id}` });
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
