const auth = require("../../utils/auth");
const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");

const CONTACTS = [
  { name: "邝大程", job: "队长", phone: "15766508094" },
  { name: "黄灵媛", job: "副秘书长", phone: "15766508094" }
];

const PROTOCOLS = [
  { id: "p001", title: "雷州市恒德爱心公益协会***协议", content: "雷州市恒德爱心公益协会***协议" }
];

const SUGGESTIONS = [
  {
    id: "c001",
    type: "投诉",
    title: "投诉标题",
    submitNo: "JC202412020001",
    submitTime: "2024/12/02 22:20",
    acceptTime: "",
    replyTime: "",
    result: "待受理",
    status: "待受理",
    department: ""
  },
  {
    id: "c002",
    type: "投诉",
    title: "投诉标题",
    submitNo: "JC202412020002",
    submitTime: "2024/12/02 22:20",
    acceptTime: "2024/12/10 22:20",
    replyTime: "",
    result: "处理中",
    status: "处理中",
    department: ""
  },
  {
    id: "c003",
    type: "建议",
    title: "投诉标题",
    submitNo: "JY202412020003",
    submitTime: "2024/12/02 22:20",
    acceptTime: "2024/12/10 22:20",
    replyTime: "2024/12/15 22:20",
    result: "已处理",
    status: "已处理",
    department: "组织部"
  }
];

Page({
  data: {
    mode: "main",
    navTitle: "安全中心",
    contacts: CONTACTS,
    protocols: PROTOCOLS,
    suggestions: SUGGESTIONS,
    suggestionDetail: {
      submitNo: "JC202412020002",
      status: "处理中",
      submitTime: "2024/12/02 22:20",
      acceptTime: "2024/12/10 22:20",
      replyTime: "",
      replyDept: "组织部",
      title: "【投诉】投诉标题",
      content: "阿爸巴巴爸爸",
      replyContent: "阿爸巴巴爸爸"
    },
    signatureName: "邝大程",
    oldPasswordValue: "",
    newPasswordValue: "",
    phoneValue: "",
    aboutText: "阿八八八",
    newMaterialNo: "JC202412020002",
    showCancelModal: false
  },

  onLoad(query) {
    if (query.mode) {
      this.switchMode(query.mode);
    }
  },

  switchMode(mode) {
    const titleMap = {
      main: "安全中心",
      password: "密码修改",
      phone: "手机号修改",
      about: "关于我们",
      contact: "联系我们",
      signature: "手写签名板",
      protocol: "协议中心",
      protocolDetail: "协议详情",
      suggestList: "建议与投诉",
      suggestDetail: "投诉建议详情",
      suggestAdd: "建议与投诉",
      materialAdd: "新增材料"
    };
    this.setData({
      mode,
      navTitle: titleMap[mode] || "安全中心",
      showCancelModal: false
    });
  },

  goBack() {
    if (this.data.mode !== "main") {
      this.switchMode("main");
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/mine/index" });
  },

  tapItem(event) {
    const action = event.currentTarget.dataset.action;
    if (action === "logout") {
      this.logout();
      return;
    }
    if (action === "password") this.switchMode("password");
    if (action === "phone") this.switchMode("phone");
    if (action === "about") this.switchMode("about");
    if (action === "contact") this.switchMode("contact");
    if (action === "signature") this.switchMode("signature");
    if (action === "protocol") this.switchMode("protocol");
    if (action === "suggest") this.switchMode("suggestList");
  },

  chooseProtocol(event) {
    const id = event.currentTarget.dataset.id;
    const protocol = this.data.protocols.find((item) => item.id === id) || this.data.protocols[0];
    this.setData({
      protocolDetail: protocol
    });
    this.switchMode("protocolDetail");
  },

  chooseSuggestion(event) {
    const id = event.currentTarget.dataset.id;
    const suggestion = this.data.suggestions.find((item) => item.id === id) || this.data.suggestionDetail;
    this.setData({ suggestionDetail: suggestion });
    this.switchMode("suggestDetail");
  },

  openSuggestionAdd() {
    this.switchMode("suggestAdd");
  },

  openMaterialAdd() {
    this.switchMode("materialAdd");
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: event.detail.value });
  },

  chooseSignature() {
    this.setData({ signatureName: "邝大程" });
  },

  // 设置/修改登录密码：PUT /v/auth/password（首次设密码原密码可留空）。设密码后可用手机号+密码登录。
  async savePassword() {
    const newPassword = (this.data.newPasswordValue || "").trim();
    if (newPassword.length < 6 || newPassword.length > 32) {
      wx.showToast({ title: "新密码需 6-32 位", icon: "none" });
      return;
    }
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.passwordChange,
        method: "PUT",
        data: { oldPassword: this.data.oldPasswordValue || "", newPassword }
      });
      wx.showToast({ title: "密码已保存", icon: "success" });
      this.setData({ oldPasswordValue: "", newPasswordValue: "" });
      setTimeout(() => this.switchMode("main"), 600);
    } catch (error) {
      wx.showToast({ title: error.message || "保存失败", icon: "none" });
    }
  },

  savePhone() {
    wx.showToast({ title: "已保存", icon: "none" });
  },

  confirmAbout() {
    wx.showToast({ title: "确定", icon: "none" });
  },

  callPhone(event) {
    const phone = event.currentTarget.dataset.phone;
    wx.makePhoneCall({ phoneNumber: phone });
  },

  saveSignature() {
    wx.showToast({ title: "已保存", icon: "none" });
  },

  confirmProtocol() {
    this.switchMode("protocol");
  },

  logout() {
    wx.showModal({
      title: "退出登录",
      content: "确认退出当前账号吗？",
      confirmText: "退出",
      success: async (result) => {
        if (!result.confirm) return;
        await this.callLogout();
        auth.clearLogin();
        wx.reLaunch({ url: "/pages/login/index" });
      }
    });
  },

  async callLogout() {
    const role = auth.getRole();
    const logoutUrl = role === "admin" ? ENDPOINTS.admin.auth.logout : ENDPOINTS.volunteer.auth.logout;
    try {
      await request({ url: logoutUrl, method: "POST" });
    } catch (error) {}
  },

  cancelSuggestion() {
    this.setData({ showCancelModal: true });
  },

  confirmCancel() {
    this.setData({ showCancelModal: false });
    wx.showToast({ title: "已撤销", icon: "none" });
  },

  cancelCancel() {
    this.setData({ showCancelModal: false });
  }
});
