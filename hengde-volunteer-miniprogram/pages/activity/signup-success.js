const DEFAULT_DATA = {
  activityInfo: {
    title: "这是一个活动名称",
    dateRange: "2025/02/03 至 03/03",
    place: "雷州市西湖街道西湖新村17号"
  },
  person: {
    name: "邝大程",
    phone: "13800000001",
    school: "雷州市第二中学"
  },
  sessions: [
    { id: "20250204-am", dateShort: "02月04日", time: "9:00-12:00", post: "志愿者" },
    { id: "20250204-noon", dateShort: "02月04日", time: "12:00-14:00", post: "志愿者" }
  ]
};

Page({
  data: DEFAULT_DATA,

  onLoad() {
    const saved = wx.getStorageSync("activitySignupSuccess");
    if (saved) {
      this.setData(saved);
    }
  },

  goBack() {
    wx.navigateBack();
  },

  returnBack() {
    wx.navigateBack({ delta: 2 });
  }
});
