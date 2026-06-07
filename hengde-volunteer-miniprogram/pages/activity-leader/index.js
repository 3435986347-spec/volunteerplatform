const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

Page({
  data: {
    canPublish: false,
    permissionUnknown: false,
    activities: [],
    loading: false,
    emptyText: "暂无数据"
  },

  onShow() {
    if (!auth.requireLogin()) return;
    this.loadPermissionsAndActivities();
  },

  async loadPermissionsAndActivities() {
    this.setData({ loading: true });
    let permissionUnknown = false;
    try {
      const permissions = await dataService.loadMyPermissions();
      auth.setPermissions(permissions);
    } catch (error) {
      permissionUnknown = true;
      if (!auth.getPermissions().length) {
        auth.setPermissions([]);
      }
    }

    const canPublish = permissionUnknown || auth.hasPermission("activity:publish");
    this.setData({
      canPublish,
      permissionUnknown,
      emptyText: "暂无数据"
    });

    try {
      const activities = await dataService.listManagedActivities();
      this.setData({
        activities,
        loading: false,
        emptyText: activities.length || canPublish ? "" : "暂无数据"
      });
    } catch (error) {
      this.setData({
        activities: [],
        loading: false,
        emptyText: canPublish ? "" : "暂无数据"
      });
    }
  },

  goBack() {
    wx.switchTab({ url: "/pages/mine/index" });
  },

  manageActivity(event) {
    wx.navigateTo({ url: `/pages/activity-leader/detail?id=${event.currentTarget.dataset.id}` });
  },

  publishActivity() {
    if (!this.data.canPublish && !this.data.permissionUnknown) {
      wx.showToast({ title: "无权限", icon: "none" });
      return;
    }
    wx.navigateTo({ url: "/pages/activity-leader/publish" });
  }
});
