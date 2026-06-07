const dataService = require("../../utils/data-service");

const STATUS_OPTIONS = ["全部状态", "未开放", "报名中", "报名截止", "活动中", "已结束"];
const SORT_OPTIONS = [
  "默认排序",
  "报名人数最多",
  "报名人数最少",
  "浏览次数最多",
  "浏览次数最少",
  "剩余名额最多",
  "活动时间升序",
  "活动时间降序"
];

function statusKey(status) {
  const map = {
    "未开放": "pending",
    "报名中": "open",
    "报名截止": "closed",
    "活动中": "active",
    "已结束": "ended"
  };
  return map[status] || "pending";
}

function toTimestamp(activity) {
  const startTime = String(activity.time || "").split("-")[0];
  const text = `${activity.date || ""} ${startTime}`.replace(/-/g, "/");
  const time = Date.parse(text);
  return Number.isNaN(time) ? 0 : time;
}

function normalizeActivity(activity) {
  const joined = Number(activity.joined || 0);
  const quota = Number(activity.quota || 0);
  const timeText = activity.time ? ` ${activity.time}` : "";
  return Object.assign({}, activity, {
    joined,
    quota,
    remain: Math.max(quota - joined, 0),
    views: Number(activity.views || activity.viewCount || activity.browseCount || 0),
    statusKey: statusKey(activity.status),
    timeText,
    listImage: activity.coverImageUrl || activity.imageUrl || "/assets/icons/activity-logo.png"
  });
}

Page({
  data: {
    keyword: "",
    statusIndex: 0,
    sortIndex: 0,
    dateValue: "",
    activeStatusText: "",
    activeSortText: "",
    statusOptions: STATUS_OPTIONS,
    sortOptions: SORT_OPTIONS,
    allActivities: [],
    activities: [],
    activityImage: "/assets/icons/activity-logo.png"
  },

  onLoad() {
    this.loadActivities();
  },

  async loadActivities() {
    try {
      const activities = (await dataService.listActivities()).map(normalizeActivity);
      this.setData({ allActivities: activities }, () => this.applyFilters());
    } catch (error) {
      wx.showToast({ title: "活动接口暂不可用", icon: "none" });
    }
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value || "" }, () => this.applyFilters());
  },

  onStatusChange(event) {
    const statusIndex = Number(event.detail.value);
    this.setData({
      statusIndex,
      activeStatusText: statusIndex > 0 ? STATUS_OPTIONS[statusIndex] : ""
    }, () => this.applyFilters());
  },

  onDateChange(event) {
    this.setData({ dateValue: event.detail.value || "" }, () => this.applyFilters());
  },

  onSortChange(event) {
    const sortIndex = Number(event.detail.value);
    this.setData({
      sortIndex,
      activeSortText: sortIndex > 0 ? SORT_OPTIONS[sortIndex] : ""
    }, () => this.applyFilters());
  },

  clearFilters() {
    this.setData({
      keyword: "",
      statusIndex: 0,
      sortIndex: 0,
      dateValue: "",
      activeStatusText: "",
      activeSortText: ""
    }, () => this.applyFilters());
  },

  applyFilters() {
    const keyword = (this.data.keyword || "").trim();
    const status = STATUS_OPTIONS[this.data.statusIndex];
    const sort = SORT_OPTIONS[this.data.sortIndex];
    let activities = this.data.allActivities.slice();

    if (keyword) {
      activities = activities.filter((item) => {
        return [item.title, item.place, item.summary, item.no].some((value) => String(value || "").includes(keyword));
      });
    }

    if (this.data.statusIndex > 0) {
      activities = activities.filter((item) => item.status === status);
    }

    if (this.data.dateValue) {
      activities = activities.filter((item) => item.date === this.data.dateValue);
    }

    if (sort === "报名人数最多") {
      activities.sort((a, b) => b.joined - a.joined);
    } else if (sort === "报名人数最少") {
      activities.sort((a, b) => a.joined - b.joined);
    } else if (sort === "浏览次数最多") {
      activities.sort((a, b) => b.views - a.views);
    } else if (sort === "浏览次数最少") {
      activities.sort((a, b) => a.views - b.views);
    } else if (sort === "剩余名额最多") {
      activities.sort((a, b) => b.remain - a.remain);
    } else if (sort === "活动时间升序") {
      activities.sort((a, b) => toTimestamp(a) - toTimestamp(b));
    } else if (sort === "活动时间降序") {
      activities.sort((a, b) => toTimestamp(b) - toTimestamp(a));
    }

    this.setData({ activities });
  },

  goDetail(event) {
    wx.navigateTo({ url: `/pages/activity/detail?id=${event.currentTarget.dataset.id}` });
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
