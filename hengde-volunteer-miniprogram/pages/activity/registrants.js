const dataService = require("../../utils/data-service");

const DEFAULT_ROWS = [
  { id: "u001", name: "周怡汐", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/01 18:20", postTime: "2026/06/15 17:00-19:00" },
  { id: "u002", name: "曾嘉豪", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/01 18:32", postTime: "2026/06/15 17:00-19:00" }
];

function normalizeRows(rows) {
  const source = Array.isArray(rows) && rows.length ? rows : DEFAULT_ROWS;
  return source.map((item, index) => ({
    id: item.id || `registrant-${index}`,
    name: item.name || item.realName || "志愿者",
    avatar: item.avatar || item.avatarUrl || "/assets/icons/activity-logo.png",
    signupTime: item.signupTime || "待记录",
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
