const dataService = require("../../utils/data-service");

Page({
  data: {
    records: []
  },

  onLoad() {
    this.loadRecords();
  },

  async loadRecords() {
    try {
      const records = await dataService.listServiceRecords();
      this.setData({ records });
    } catch (error) {
      wx.showToast({ title: "服务记录暂不可用", icon: "none" });
    }
  },

  goActivity(event) {
    const id = event.currentTarget.dataset.id;
    if (!id) return;
    wx.navigateTo({ url: `/pages/activity/detail?id=${id}` });
  }
});
