const dataService = require("../../utils/data-service");

function displayTeam(row) {
  const belonged = row.belonged === true || row.belonged === 1;
  return {
    id: row.id,
    type: row.type || "分队",
    name: row.name,
    volunteerCount: row.memberCount || row.members || 0,
    contact: row.leader || "待设置",
    phone: row.leaderPhone || "",
    status: belonged ? "已通过" : "申请",
    joinText: belonged ? "查看" : "申请",
    actionText: belonged ? "退出" : "取消",
    canView: belonged,
    raw: row
  };
}

function displayMember(row) {
  return {
    role: row.role || row.roleName || "志愿者",
    name: row.realName || row.name || row.volunteerName || "",
    extra: row.grade || row.school || "",
    date: row.joinTime ? String(row.joinTime).replace("T", " ").slice(0, 10) : ""
  };
}

Page({
  data: {
    mode: "list",
    navTitle: "归属组织",
    activeTab: 0,
    schoolTeams: [],
    townTeams: [],
    selectedTeam: {},
    detailMembers: [],
    joinedState: "",
    showApplySuccess: false,
    mySchoolTeam: { type: "中学分队", name: "未归属", state: "未申请", actionText: "申请" },
    myTownTeam: { type: "乡镇分队", name: "未归属", state: "未申请", actionText: "申请" }
  },

  onLoad() {
    this.loadSquads();
  },

  async loadSquads() {
    try {
      const teams = (await dataService.listSquads()).map(displayTeam);
      const schoolTeams = teams.filter((item) => /学校|中学|小学|高中|大学/.test(item.type + item.name));
      const townTeams = teams.filter((item) => !schoolTeams.some((school) => school.id === item.id));
      const schoolCurrent = schoolTeams.find((item) => item.status === "已通过");
      const townCurrent = townTeams.find((item) => item.status === "已通过" || item.status === "审核中");
      this.setData({
        schoolTeams,
        townTeams,
        mySchoolTeam: schoolCurrent ? {
          type: schoolCurrent.type,
          name: schoolCurrent.name,
          state: schoolCurrent.status,
          actionText: "查看"
        } : this.data.mySchoolTeam,
        myTownTeam: townCurrent ? {
          type: townCurrent.type,
          name: townCurrent.name,
          state: townCurrent.status,
          actionText: townCurrent.status === "已通过" ? "查看" : "取消"
        } : this.data.myTownTeam
      });
    } catch (error) {
      wx.showToast({ title: "分队接口暂不可用", icon: "none" });
    }
  },

  goBack() {
    if (this.data.mode === "detail") {
      this.setData({ mode: "list", navTitle: "归属组织" });
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/mine/index" });
  },

  switchTab(event) {
    this.setData({ activeTab: Number(event.currentTarget.dataset.index) });
  },

  async applyTeam(event) {
    const id = event.currentTarget.dataset.id;
    const team = [...this.data.schoolTeams, ...this.data.townTeams].find((item) => item.id === id);
    if (!team) return;
    if (team.status === "已通过") {
      this.openDetail(team);
      return;
    }
    try {
      await dataService.applySquad(id, { reason: "申请加入分队" });
      this.showApplySuccessToast();
      this.loadSquads();
    } catch (error) {
      wx.showToast({ title: error.message || "申请失败", icon: "none" });
    }
  },

  showApplySuccessToast() {
    if (this.applySuccessTimer) clearTimeout(this.applySuccessTimer);
    this.setData({ showApplySuccess: true });
    this.applySuccessTimer = setTimeout(() => {
      this.setData({ showApplySuccess: false });
      this.applySuccessTimer = null;
    }, 1500);
  },

  handleMyAction(event) {
    const action = event.currentTarget.dataset.action;
    const list = event.currentTarget.dataset.type === "town" ? this.data.townTeams : this.data.schoolTeams;
    const team = list.find((item) => item.status === "已通过") || list[0];
    if (!team) return;
    if (action === "查看") {
      this.openDetail(team);
      return;
    }
    wx.showToast({ title: "请在分队详情中申请", icon: "none" });
  },

  async openDetail(team) {
    let detail = team.raw || {};
    try {
      detail = await dataService.getSquad(team.id);
    } catch (error) {
      // 列表数据足够展示基础信息。
    }
    const detailMembers = (detail.memberList || []).map(displayMember);
    this.setData({
      mode: "detail",
      navTitle: "归属组织",
      selectedTeam: Object.assign({}, team, displayTeam(detail)),
      detailMembers,
      joinedState: team.status
    });
  },

  exitTeam() {
    wx.showToast({ title: "退出分队接口后端暂未提供", icon: "none" });
  }
});
