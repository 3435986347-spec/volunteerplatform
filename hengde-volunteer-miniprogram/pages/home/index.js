const dataService = require("../../utils/data-service");
const auth = require("../../utils/auth");

const quickActions = [
  { label: "志愿者注册", icon: "/assets/icons/register.png", url: "/pages/register/index" },
  { label: "活动相册", icon: "/assets/icons/album.png" },
  { label: "志愿活动", icon: "/assets/icons/activity.png", url: "/pages/activity/list" },
  { label: "组织架构", icon: "/assets/icons/structure.png", url: "/pages/organization/squads" },
  { label: "志愿小组", icon: "/assets/icons/group.png", url: "/pages/organization/groups" },
  { label: "积分兑换", icon: "/assets/icons/points.png" },
  { label: "助学助困", icon: "/assets/icons/aid.png" },
  { label: "圆梦微心愿", icon: "/assets/icons/wish.png" },
  { label: "名单公示", icon: "/assets/icons/publicity.png", url: "/pages/publicity/notices" },
  { label: "项目众筹", icon: "/assets/icons/crowdfunding.png" },
  { label: "临时负责人申请", icon: "/assets/icons/leader.png" },
  { label: "文件下载", icon: "/assets/icons/download.png" },
  { label: "爱心企业", icon: "/assets/icons/enterprise.png" },
  { label: "排行榜", icon: "/assets/icons/ranking.png" },
  { label: "榜样", icon: "/assets/icons/model.png" },
  { label: "公益捐书", icon: "/assets/icons/book.png" }
];

function chunkActions(actions, size) {
  const pages = [];
  for (let index = 0; index < actions.length; index += size) {
    pages.push(actions.slice(index, index + size));
  }
  return pages;
}

const STATUS_CLASS_MAP = {
  "未开放": "status-not-open",
  "报名中": "status-enrolling",
  "报名截止": "status-closed"
};

const ROLE_LABEL_MAP = {
  visitor: "游客",
  volunteer: "志愿者",
  admin: "管理团队",
  enterprise: "爱心企业"
};

function withStatusClass(activity) {
  return Object.assign({}, activity, {
    statusClass: STATUS_CLASS_MAP[activity.status] || ""
  });
}

Page({
  data: {
    banners: [],
    bannerCurrent: 0,
    notices: [],
    homeNotice: null,
    dashboard: [],
    activities: [],
    scanIcon: "/assets/icons/scan.png",
    dashboardIcon: "/assets/icons/dashboard.png",
    activityLogo: "/assets/icons/activity-logo.png",
    roleLabel: "游客",
    quickActionPages: chunkActions(quickActions, 8)
  },

  onLoad() {
    this.loadHome();
  },

  onShow() {
    if (!auth.requireLogin()) {
      this.setData({ roleLabel: ROLE_LABEL_MAP.visitor });
      return;
    }

    this.refreshRoleLabel();

    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) {
      tabBar.setData({ selected: 0 });
    }
  },

  async loadHome() {
    try {
      const data = await dataService.loadHomeData();
      this.setData({
        banners: data.banners || [],
        notices: data.notices || [],
        homeNotice: data.notices && data.notices.length ? data.notices[0] : null,
        dashboard: data.dashboard || [],
        activities: (data.activities || []).map(withStatusClass)
      });
    } catch (error) {
      wx.showToast({ title: "首页接口暂不可用", icon: "none" });
    }
  },

  refreshRoleLabel() {
    const role = auth.getRole();
    this.setData({
      roleLabel: ROLE_LABEL_MAP[role] || ROLE_LABEL_MAP.visitor
    });
  },

  goSearch() {
    wx.navigateTo({ url: "/pages/search/index" });
  },

  goPage(event) {
    const url = event.currentTarget.dataset.url;
    if (!url) {
      wx.showToast({ title: "V1 暂缓", icon: "none" });
      return;
    }
    wx.navigateTo({ url });
  },

  onBannerChange(event) {
    this.setData({
      bannerCurrent: event.detail.current
    });
  },

  openLinkedResource(item, fallbackUrl) {
    if (item && Number(item.linkType) === 2 && item.appId) {
      wx.navigateToMiniProgram({
        appId: item.appId,
        path: item.linkUrl || "",
        fail() {
          wx.showToast({ title: "其他小程序跳转接口已预留", icon: "none" });
        }
      });
      return;
    }
    if (item && Number(item.linkType) === 2 && item.linkUrl) {
      wx.navigateTo({ url: item.linkUrl });
      return;
    }
    if (item && Number(item.linkType) === 1 && item.linkUrl) {
      wx.setClipboardData({
        data: item.linkUrl,
        success() {
          wx.showToast({ title: "推文链接已复制", icon: "none" });
        }
      });
      return;
    }
    if (fallbackUrl) {
      wx.navigateTo({ url: fallbackUrl });
    }
  },

  openBanner(event) {
    const id = String(event.currentTarget.dataset.id || "");
    const banner = (this.data.banners || []).find((item) => String(item.id) === id);
    if (!banner) {
      wx.showToast({ title: "暂无轮播图", icon: "none" });
      return;
    }
    this.openLinkedResource(banner);
  },

  openHomeNotice() {
    const notice = this.data.homeNotice;
    if (!notice) return;
    this.openLinkedResource(notice, `/pages/publicity/notice-detail?id=${notice.id}`);
  },

  goNotice(event) {
    wx.navigateTo({ url: `/pages/publicity/notice-detail?id=${event.currentTarget.dataset.id}` });
  },

  goActivity(event) {
    wx.navigateTo({ url: `/pages/activity/detail?id=${event.currentTarget.dataset.id}` });
  }
});
