const dataService = require("../../utils/data-service");

function normalizeGroup(group, index) {
  return {
    id: group.id,
    no: group.no || group.groupNo || String(12511 + index),
    name: group.name || "这是一个小组名称",
    leader: group.leader || "邝大程",
    createdAt: group.createdAt || group.createTime || "2025/08/04",
    members: group.members || group.memberCount || 320,
    logo: group.logo || "/assets/icons/activity-logo.png",
    intro: group.intro || "这是一个小组简介"
  };
}

Page({
  data: {
    keyword: "",
    allGroups: [],
    groups: []
  },

  onLoad() {
    this.loadGroups();
  },

  async loadGroups() {
    try {
      const groups = (await dataService.listGroups()).map(normalizeGroup);
      this.setData({
        allGroups: groups,
        groups
      });
    } catch (error) {
      wx.showToast({ title: "小组接口暂不可用", icon: "none" });
    }
  },

  onSearchInput(event) {
    const keyword = (event.detail.value || "").trim();
    const groups = this.data.allGroups.filter((group) => {
      return group.name.includes(keyword) || String(group.no).includes(keyword);
    });
    this.setData({
      keyword,
      groups
    });
  },

  goCreate() {
    wx.navigateTo({ url: "/pages/organization/group-create" });
  },

  goDetail(event) {
    wx.navigateTo({ url: `/pages/organization/group-detail?id=${event.currentTarget.dataset.id}&mode=join` });
  },

  goApply(event) {
    const id = event.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/organization/group-detail?id=${id}&mode=join` });
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/home/index" });
  }
});
