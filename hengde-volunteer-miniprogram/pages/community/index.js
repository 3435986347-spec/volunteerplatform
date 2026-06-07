const auth = require("../../utils/auth");

Page({
  data: {
    tabs: ["最热", "关注", "官方", "私信", "互动"],
    activeTab: "最热",
    logoIcon: "/assets/icons/activity-logo.png",
    imageIcon: "/assets/icons/album.png",
    posts: [
      {
        id: "post001",
        nickname: "这是昵称",
        time: "2025/03/05 12:00",
        followed: false,
        content: "这道题怎么写",
        mediaType: "image",
        views: 321,
        shares: 3,
        likes: 20,
        comments: 30
      },
      {
        id: "post002",
        nickname: "这是昵称",
        time: "2025/03/05 12:00",
        followed: true,
        content: "这个活动好好玩，#客运站活动",
        mediaType: "phone-grid",
        views: 321,
        shares: 3,
        likes: 20,
        comments: 30
      }
    ]
  },

  onShow() {
    if (!auth.requireLogin()) {
      return;
    }

    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) {
      tabBar.setData({ selected: 1 });
    }
  },

  switchTab(event) {
    this.setData({ activeTab: event.currentTarget.dataset.tab });
  },

  goDetail(event) {
    wx.navigateTo({ url: `/pages/community/detail?id=${event.currentTarget.dataset.id}` });
  }
});
