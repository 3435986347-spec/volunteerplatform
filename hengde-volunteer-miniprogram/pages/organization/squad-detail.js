const dataService = require("../../utils/data-service");

function decode(value, fallback) {
  return value ? decodeURIComponent(value) : fallback;
}

function displayMember(row, index) {
  return {
    id: String(row.id || row.volunteerId || `member-${index}`),
    name: row.realName || row.name || row.volunteerName || "",
    position: row.position || row.roleName || "志愿者",
    phone: row.phone || row.mobile || "",
    school: row.school || row.schoolName || ""
  };
}

Page({
  data: {
    keyword: "",
    squadId: "",
    teamName: "组织架构",
    departmentName: "成员",
    members: [],
    displayMembers: [],
    logo: "/assets/icons/activity-logo.png"
  },

  onLoad(query = {}) {
    const squadId = query.id || "";
    this.setData({
      squadId,
      teamName: decode(query.team, "组织架构"),
      departmentName: decode(query.department, "成员")
    });
    this.loadDetail();
  },

  async loadDetail() {
    if (!this.data.squadId) return;
    try {
      const detail = await dataService.getSquad(this.data.squadId);
      const members = (detail.memberList || []).map(displayMember);
      this.setData({
        teamName: detail.name || this.data.teamName,
        departmentName: detail.type || this.data.departmentName,
        members,
        displayMembers: members
      });
    } catch (error) {
      wx.showToast({ title: "组织详情暂不可用", icon: "none" });
    }
  },

  goBack() {
    wx.navigateBack();
  },

  updateKeyword(event) {
    const keyword = event.detail.value;
    const word = String(keyword || "").trim();
    const displayMembers = word
      ? this.data.members.filter((item) => item.name.includes(word) || item.position.includes(word) || item.school.includes(word))
      : this.data.members;
    this.setData({ keyword, displayMembers });
  },

  messageMember() {
    wx.showToast({ title: "消息功能后续版本开放", icon: "none" });
  },

  callMember(event) {
    const phone = event.currentTarget.dataset.phone;
    if (!phone) return;
    wx.makePhoneCall({ phoneNumber: phone });
  }
});
