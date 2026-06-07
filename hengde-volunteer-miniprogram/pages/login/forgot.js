Page({
  data: {
    icons: {
      back: "/assets/login/back-transparent.png",
      contactCard: "/assets/login/forgot-contact-card.png"
    }
  },

  confirm() {
    wx.navigateBack();
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
