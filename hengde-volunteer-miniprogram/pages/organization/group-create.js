const mock = require("../../utils/mock");
const dataService = require("../../utils/data-service");

Page({
  data: {
    logo: "",
    name: "",
    intro: "",
    points: mock.profile.points || 0,
    sponsor: {
      name: mock.profile.name || "邝大程",
      phone: mock.profile.phone || "138****7788",
      school: "雷州市第二中学"
    }
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.navigateTo({ url: "/pages/organization/groups" });
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    this.setData({ [field]: event.detail.value });
  },

  chooseLogo() {
    const chooseMedia = wx.chooseMedia || wx.chooseImage;
    if (!chooseMedia) {
      wx.showToast({ title: "当前版本不支持上传图片", icon: "none" });
      return;
    }
    chooseMedia({
      count: 1,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: (res) => {
        const file = res.tempFiles ? res.tempFiles[0] : res.tempFilePaths && { tempFilePath: res.tempFilePaths[0] };
        this.setData({ logo: file ? file.tempFilePath : "" });
      }
    });
  },

  async submitGroup() {
    const name = (this.data.name || "").trim();
    const intro = (this.data.intro || "").trim();
    if (!name) {
      wx.showToast({ title: "请输入小组名称", icon: "none" });
      return;
    }
    if (!intro) {
      wx.showToast({ title: "请输入小组简介", icon: "none" });
      return;
    }
    if (Number(this.data.points || 0) < 50) {
      wx.showToast({ title: "积分不足，无法发起小组", icon: "none" });
      return;
    }
    try {
      const result = await dataService.createGroup({
        name,
        description: intro,
        logoUrl: this.data.logo || ""
      });
      wx.showToast({ title: "小组申请已提交", icon: "none" });
      setTimeout(() => {
        wx.redirectTo({ url: "/pages/organization/groups" });
      }, 500);
    } catch (error) {
      wx.showToast({ title: error.message || "小组创建失败", icon: "none" });
    }
  }
});
