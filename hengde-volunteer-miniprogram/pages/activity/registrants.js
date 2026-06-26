const dataService = require("../../utils/data-service");

function formatDateTime(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 16);
}

function normalizeRows(rows) {
  // 无真实报名数据返回空（不再用预置假人填充），由页面显示空状态
  const source = Array.isArray(rows) ? rows : [];
  return source.map((item, index) => ({
    id: item.id || `registrant-${index}`,
    name: item.name || item.realName || "志愿者",
    avatar: item.avatar || item.avatarUrl || "/assets/icons/activity-logo.png",
    signupTime: formatDateTime(item.signupTime || item.enrollTime) || "待记录",
    postTime: item.postTime || item.slotTime || "待安排",
    checkinTime: item.checkinTime || item.signInTime || "待签到",
    checkoutTime: item.checkoutTime || item.signOutTime || "待签退"
  }));
}

Page({
  data: {
    keyword: "",
    allRows: [],
    rows: []
  },

  async onLoad(query) {
    try {
      const activity = await dataService.getActivity(query.id);
      const rows = normalizeRows(activity.registrants);
      this.setData({
        allRows: rows,
        rows
      });
    } catch (error) {
      wx.showToast({ title: "报名详情暂不可用", icon: "none" });
    }
  },

  goBack() {
    wx.navigateBack();
  },

  onKeywordInput(event) {
    const keyword = event.detail.value.trim();
    const rows = keyword
      ? this.data.allRows.filter((item) => item.name.includes(keyword))
      : this.data.allRows;
    this.setData({ keyword, rows });
  }
});
