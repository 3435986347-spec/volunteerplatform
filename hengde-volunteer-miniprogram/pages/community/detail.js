Page({
  data: {
    logoIcon: "/assets/icons/activity-logo.png",
    imageIcon: "/assets/icons/album.png",
    comments: [
      {
        nickname: "这是昵称",
        content: "先这样，写然后那样写就好了",
        time: "03/05 12:01",
        replies: [
          { nickname: "我是空气", content: "我也这么觉得", time: "03/05 12:03" },
          { nickname: "滴滴答答滴答", content: "你们真聪明", time: "03/05 12:05" }
        ]
      },
      {
        nickname: "心中有月",
        content: "小朋友真棒，哥哥也不知道",
        time: "03/05 12:01",
        replies: []
      }
    ]
  },

  goBack() {
    wx.navigateBack();
  }
});
