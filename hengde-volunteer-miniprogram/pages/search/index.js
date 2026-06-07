const dataService = require("../../utils/data-service");

const PAGE_SIZE = 10;

function typeLabel(type) {
  const map = {
    activity: "活动标签",
    announcement: "公告",
    group: "志愿小组",
    squad: "分队"
  };
  return map[type] || "结果";
}

function withDisplayFields(item) {
  return Object.assign({}, item, {
    key: `${item.type}-${item.id}`,
    typeLabel: typeLabel(item.type)
  });
}

Page({
  data: {
    keyword: "",
    page: 1,
    results: [],
    hasMore: false,
    loading: false,
    searched: false
  },

  onLoad(query) {
    const keyword = query.keyword || "";
    this.setData({ keyword });
    if (keyword) {
      this.search(true);
    }
  },

  onUnload() {
    clearTimeout(this.searchTimer);
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value || "" });
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.search(true);
    }, 300);
  },

  onSearchConfirm() {
    clearTimeout(this.searchTimer);
    this.search(true);
  },

  async search(reset) {
    const keyword = (this.data.keyword || "").trim();
    if (!keyword) {
      this.setData({
        page: 1,
        results: [],
        hasMore: false,
        searched: true
      });
      return;
    }

    if (this.data.loading) {
      return;
    }

    const page = reset ? 1 : this.data.page;
    this.setData({ loading: true });

    try {
      const result = await dataService.search(keyword, page, PAGE_SIZE);
      const records = (result.records || result).map(withDisplayFields);
      this.setData({
        page: page + 1,
        results: reset ? records : this.data.results.concat(records),
        hasMore: result.total ? page * PAGE_SIZE < result.total : records.length === PAGE_SIZE,
        searched: true
      });
    } catch (error) {
      wx.showToast({ title: "搜索接口暂不可用", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  loadMore() {
    if (!this.data.hasMore) {
      return;
    }
    this.search(false);
  },

  onReachBottom() {
    this.loadMore();
  },

  goResult(event) {
    const { type, id } = event.currentTarget.dataset;
    const urls = {
      activity: `/pages/activity/detail?id=${id}`,
      announcement: `/pages/publicity/notice-detail?id=${id}`,
      group: `/pages/organization/group-detail?id=${id}`,
      squad: `/pages/organization/squad-detail?id=${id}`
    };
    const url = urls[type];
    if (!url) {
      return;
    }
    wx.navigateTo({ url });
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
