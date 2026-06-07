const dataService = require("../../utils/data-service");

Page({
  data: {
    notices: []
  },

  onLoad() {
    this.loadNotices();
  },

  async loadNotices() {
    try {
      const notices = await dataService.listNotices();
      this.setData({ notices });
    } catch (error) {
      wx.showToast({ title: "公告接口暂不可用", icon: "none" });
    }
  },

  goDetail(event) {
    wx.navigateTo({ url: `/pages/publicity/notice-detail?id=${event.currentTarget.dataset.id}` });
  },

  previewImage(event) {
    const current = event.currentTarget.dataset.src;
    const urls = this.data.notices
      .map((item) => item.coverImageUrl)
      .filter(Boolean);
    if (!current) return;
    wx.previewImage({ current, urls });
  }
});
