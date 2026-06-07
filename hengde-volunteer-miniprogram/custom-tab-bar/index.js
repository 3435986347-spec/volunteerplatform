Component({
  data: {
    selected: 0,
    color: "#111827",
    selectedColor: "#ef6b76",
    list: [
      {
        pagePath: "/pages/home/index",
        text: "首页",
        iconPath: "/assets/tabbar/home.png",
        selectedIconPath: "/assets/tabbar/home-active.png"
      },
      {
        pagePath: "/pages/community/index",
        text: "社区",
        iconPath: "/assets/tabbar/community.png",
        selectedIconPath: "/assets/tabbar/community-active.png"
      },
      {
        pagePath: "/pages/mine/index",
        text: "我的",
        iconPath: "/assets/tabbar/mine.png",
        selectedIconPath: "/assets/tabbar/mine-active.png"
      }
    ]
  },

  methods: {
    switchTab(event) {
      const data = event.currentTarget.dataset;
      wx.switchTab({ url: data.path });
    }
  }
});
