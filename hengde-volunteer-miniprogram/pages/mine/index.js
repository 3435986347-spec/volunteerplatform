const mock = require("../../utils/mock");
const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");
const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");

const ICON_DIR = "/assets/mine-v2";

const sections = [
  {
    title: "我的资料",
    items: [
      { label: "基本信息", icon: `${ICON_DIR}/basic-info.png`, url: "/pages/mine/profile?mode=basic" },
      { label: "志愿者证", icon: `${ICON_DIR}/volunteer-card.png`, url: "/pages/mine/profile?mode=volunteerCard" },
      { label: "协会证书", icon: `${ICON_DIR}/assoc-cert.png`, url: "/pages/mine/profile?mode=certList" },
      { label: "归属组织", icon: `${ICON_DIR}/organization.png`, url: "/pages/mine/my-squad" }
    ]
  },
  {
    title: "我的志愿",
    items: [
      { label: "我的活动", icon: `${ICON_DIR}/my-activities.png`, url: "/pages/mine/my-activities" },
      { label: "服务记录", icon: `${ICON_DIR}/service-record.png`, url: "/pages/mine/service-records" },
      { label: "捐赠记录", icon: `${ICON_DIR}/donate-record.png` },
      { label: "结对中心", icon: `${ICON_DIR}/pair-center.png` },
      { label: "微心愿中心", icon: `${ICON_DIR}/wish-center.png` },
      { label: "i志愿证书", icon: `${ICON_DIR}/izyz-cert.png` },
      { label: "捐书记录", icon: `${ICON_DIR}/book-donate.png` },
      { label: "积分中心", icon: `${ICON_DIR}/points-center.png` }
    ]
  },
  {
    title: "我的服务",
    items: [
      { label: "活动负责人", icon: `${ICON_DIR}/leader.png`, url: "/pages/activity-leader/index" },
      { label: "报名管理团队", icon: `${ICON_DIR}/enroll-mgmt.png` },
      { label: "评优评先", icon: `${ICON_DIR}/honor-rate.png` },
      { label: "我的保险", icon: `${ICON_DIR}/insurance.png` },
      { label: "地址管理", icon: `${ICON_DIR}/address.png` },
      { label: "安全中心", icon: `${ICON_DIR}/security.png`, url: "/pages/mine/security" },
      { label: "奖惩中心", icon: `${ICON_DIR}/reward.png` },
      { label: "投诉建议", icon: `${ICON_DIR}/complaint.png` }
    ]
  }
];

Page({
  data: {
    profile: mock.profile,
    profileLogo: "/assets/icons/activity-logo.png",
    sections
  },

  onShow() {
    if (!auth.requireLogin()) {
      return;
    }
    this.loadProfile();

    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) {
      tabBar.setData({ selected: 2 });
    }
  },

  async loadProfile() {
    try {
      const result = await dataService.getUserProfile();
      this.setData({
        profile: Object.assign({}, this.data.profile, result.profile),
        profileLogo: result.info.avatar || this.data.profileLogo
      });
    } catch (error) {
      const localUser = auth.getUser();
      if (localUser.nickName || localUser.phone) {
        this.setData({
          profile: Object.assign({}, this.data.profile, {
            name: localUser.registered && localUser.realName ? localUser.realName : localUser.nickName || localUser.phone,
            phone: localUser.phone || this.data.profile.phone,
            realNameVerified: Boolean(localUser.registered)
          })
        });
      }
    }
  },

  goPage(event) {
    const { url, action } = event.currentTarget.dataset;
    if (action === "switchAccount") {
      this.switchAccount();
      return;
    }
    if (action === "logout") {
      this.logout();
      return;
    }
    if (!url) {
      wx.showToast({ title: "V1 暂缓", icon: "none" });
      return;
    }
    wx.navigateTo({ url });
  },

  switchAccount() {
    auth.clearLogin();
    wx.reLaunch({
      url: "/pages/login/index?switch=1"
    });
  },

  logout() {
    wx.showModal({
      title: "退出登录",
      content: "确认退出当前账号吗？",
      confirmText: "退出",
      success: async (result) => {
        if (!result.confirm) {
          return;
        }
        await this.callLogout();
        auth.clearLogin();
        wx.reLaunch({
          url: "/pages/login/index"
        });
      }
    });
  },

  async callLogout() {
    const role = auth.getRole();
    const logoutUrl = role === "admin" ? ENDPOINTS.admin.auth.logout : ENDPOINTS.volunteer.auth.logout;
    try {
      await request({
        url: logoutUrl,
        method: "POST"
      });
    } catch (error) {
      // 后端未启动或 token 已失效时，前端仍需要清理本地登录态。
    }
  }
});
