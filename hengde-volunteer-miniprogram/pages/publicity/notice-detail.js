const dataService = require("../../utils/data-service");

Page({
  data: {
    notice: {}
  },

  async onLoad(query) {
    try {
      const notice = await dataService.getNotice(query.id);
      this.setData({ notice });
    } catch (error) {
      wx.showToast({ title: "公告详情暂不可用", icon: "none" });
    }
  },

  previewImage() {
    const image = this.data.notice.coverImageUrl;
    if (!image) return;
    wx.previewImage({ current: image, urls: [image] });
  },

  openLink() {
    const notice = this.data.notice;
    const linkType = Number(notice.linkType);
    if (linkType === 2 && notice.linkUrl) {
      wx.navigateTo({ url: notice.linkUrl });
      return;
    }
    if (linkType === 1 && notice.linkUrl) {
      wx.setClipboardData({
        data: notice.linkUrl,
        success() {
          wx.showToast({ title: "链接已复制", icon: "none" });
        }
      });
      return;
    }
    wx.showToast({ title: "暂无跳转链接", icon: "none" });
  }
});
