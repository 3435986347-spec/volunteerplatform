const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

Page({
  data: {
    submitting: false,
    permissionUnknown: false,
    form: {
      title: "公益书屋项目书籍整理活动",
      coverImageUrl: "",
      location: "御景雅苑（东方三路）",
      startTime: "2026-06-10 09:00",
      endTime: "2026-06-10 12:00",
      enrollDeadline: "2026-06-09 18:00",
      enrollOpenManager: "2026-06-09 08:00",
      enrollOpenLeader: "2026-06-09 08:00",
      enrollOpenVolunteer: "2026-06-09 08:00",
      pointsBase: "10",
      managerMultiplier: "1.2",
      leaderMultiplier: "1.1",
      slotProjectName: "志愿者",
      slotNeedCount: "20",
      contactName: "邝大程",
      contactPhone: "15766508094",
      publisherDeptName: "组织部",
      content: "请按岗位时间到达现场，服从负责人安排。",
      requirement: "完成实名注册即可报名。",
      enrollNotice: "报名成功后请准时参加。",
      requireMinJoinCount: "0",
      requireMinJoinMinutes: "0",
      checkInRadiusM: "500",
      needAudit: "0",
      enrollScope: "0",
      lat: "",
      lng: ""
    }
  },

  async onLoad() {
    if (!auth.requireLogin()) return;
    try {
      const permissions = await dataService.loadMyPermissions();
      auth.setPermissions(permissions);
    } catch (error) {
      if (!auth.getPermissions().length) auth.setPermissions([]);
      this.setData({ permissionUnknown: true });
    }
    if (!this.data.permissionUnknown && !auth.hasPermission("activity:publish")) {
      wx.showToast({ title: "暂无权限", icon: "none" });
      wx.navigateBack();
    }
  },

  goBack() {
    wx.navigateBack();
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({
      [`form.${field}`]: event.detail.value
    });
  },

  async submitPublish() {
    const { title, location, startTime, endTime, slotNeedCount } = this.data.form;
    if (!title || !location || !startTime || !endTime || !slotNeedCount) {
      wx.showToast({ title: "请填写完整活动信息", icon: "none" });
      return;
    }
    if (!this.data.permissionUnknown && !auth.hasPermission("activity:publish")) {
      wx.showToast({ title: "暂无权限", icon: "none" });
      return;
    }

    this.setData({ submitting: true });
    try {
      await dataService.publishActivity(this.data.form);
      wx.showToast({ title: "发布成功", icon: "success" });
      setTimeout(() => wx.redirectTo({ url: "/pages/activity-leader/index" }), 700);
    } catch (error) {
      wx.showToast({ title: error.message === "HTTP 403" ? "暂无权限" : "发布失败", icon: "none" });
      this.setData({ submitting: false });
    }
  }
});
