const dataService = require("../../utils/data-service");

function filterRows(rows, keyword) {
  const word = String(keyword || "").trim();
  if (!word) return rows;
  return rows.filter((item) => String(item.name || "").includes(word));
}

function normalizeNode(row, index) {
  return {
    id: String(row.id || row.nodeId || row.squadId || `node-${index}`),
    name: row.name || row.squadName || row.title || "未命名组织",
    count: row.count || row.memberCount || row.members || 0,
    children: Array.isArray(row.children) ? row.children : [],
    kind: row.squadId || row.type ? "squad" : "node"
  };
}

Page({
  data: {
    keyword: "",
    selectedTeam: null,
    rootItems: [],
    displayRows: []
  },

  onLoad() {
    this.loadStructure();
  },

  async loadStructure() {
    try {
      let rows = await dataService.listStructure();
      if (!rows.length) {
        rows = await dataService.listSquads();
      }
      const rootItems = rows.map(normalizeNode);
      this.setData({ rootItems }, () => this.refreshRows());
    } catch (error) {
      wx.showToast({ title: "组织架构暂不可用", icon: "none" });
    }
  },

  goBack() {
    if (this.data.selectedTeam) {
      this.setData({ selectedTeam: null, keyword: "" }, () => this.refreshRows());
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/mine/index" });
  },

  updateKeyword(event) {
    this.setData({ keyword: event.detail.value }, () => this.refreshRows());
  },

  refreshRows() {
    const rows = this.data.selectedTeam ? this.data.selectedTeam.children.map(normalizeNode) : this.data.rootItems;
    this.setData({ displayRows: filterRows(rows, this.data.keyword) });
  },

  openRow(event) {
    const id = event.currentTarget.dataset.id;
    const row = this.data.displayRows.find((item) => item.id === id);
    if (!row) return;
    if (row.children && row.children.length) {
      this.setData({ selectedTeam: row, keyword: "" }, () => this.refreshRows());
      return;
    }
    wx.navigateTo({
      url: `/pages/organization/squad-detail?id=${encodeURIComponent(row.id)}&team=${encodeURIComponent(row.name)}`
    });
  }
});
