const dataService = require("../../utils/data-service");
const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");

const POLITICS = ["群众", "共青团员", "中共预备党员", "中共党员", "民主党派"];

// PPT 原件说明：
// 1) 后台数据的年级要实时随着时间自动更新。
// 2) 志愿者端每学年需要确认学校和年级。
// 3) 学校确认状态包括：待确认、待审核、已确认。
// 4) 年级选项从毕业倒到一年级。
const GRADES = [
  "毕业",
  "大五",
  "大四",
  "大三",
  "大二",
  "大一",
  "高三年级",
  "高二年级",
  "高一年级",
  "九年级",
  "八年级",
  "七年级",
  "六年级",
  "五年级",
  "四年级",
  "三年级",
  "二年级",
  "一年级"
];

const CERTIFICATES = [
  {
    id: "cert001",
    title: "这是一个活动名称",
    date: "2025年3月2日",
    time: "9:00/12:00",
    post: "志愿者",
    type: "志愿活动",
    tagClass: "blue",
    amount: "￥0",
    no: "564641541665184",
    selected: false
  },
  {
    id: "cert002",
    title: "这是一个活动名称",
    object: "邝大程",
    donate: "20元",
    donateTime: "2025年3月2日",
    date: "2025年3月2日",
    type: "助学助困",
    tagClass: "pink",
    amount: "￥3",
    no: "564641541665156",
    selected: false
  },
  {
    id: "cert003",
    title: "这是一个活动名称",
    number: "2420454",
    object: "邝大程",
    date: "2025/03/02",
    type: "微心愿",
    tagClass: "gray",
    amount: "￥3",
    no: "564641541665156",
    selected: true
  },
  {
    id: "cert004",
    title: "这是一个活动名称",
    date: "2025/03/02",
    type: "公益捐书",
    tagClass: "red",
    amount: "￥6",
    no: "564641541665156",
    selected: true
  }
];

Page({
  data: {
    mode: "main",
    entryMode: "main",
    navTitle: "我的",
    profile: {
      realName: "邝大程",
      nickName: "沧海",
      no: "3156353651",
      title: "宣传部部长",
      activities: 3,
      hours: "32小时32分钟",
      points: 3125,
      realNameVerified: true,
      honorMedal: true,
      volunteerCard: true
    },
    info: {
      name: "邝大程",
      idType: "第二代居民身份证",
      idNo: "440882200301223999",
      phone: "15766508094",
      emergencyPhone: "17363458921",
      politics: "共青团员",
      school: "雷州市第一中学",
      grade: "高一年级",
      address: "广东省湛江市雷州市第一中学",
      nickName: "沧海",
      avatar: "",
      volunteerCode: ""
    },
    certificates: CERTIFICATES,
    filteredCertificates: CERTIFICATES,
    selectedCertificate: CERTIFICATES[0],
    selectedPaperCertificates: CERTIFICATES.filter((item) => item.selected),
    selectedDelivery: "express",
    paperApplyCount: 2,
    paperApplyTotal: "￥22",
    paperRecord: {
      recordNo: "JC202504156154",
      applyTime: "2025/02/06 22:30",
      auditTime: "2025/02/06 22:36",
      receiveTime: "2025/02/08 22:36",
      payAmount: "￥9元",
      payNo: "1869646984189365413665843684",
      urgent: "已加急",
      pickupCode: "15616554848964"
    },
    orderDetail: {
      status: "待领取",
      addressTitle: "自提地址",
      contact: "邝大程",
      phone: "15766508094",
      address: "雷州市西湖街道西湖新村17号",
      recordNo: "JC202504156154",
      orderNo: "51202504156154",
      expressNo: "JD202504156154",
      expressCompany: "京东快递",
      payAmount: "￥9元",
      payNo: "1869646984189365413665843684",
      pickupCode: "15616554848964"
    },
    searchKeyword: "",
    showPoliticsPicker: false,
    showGradePicker: false,
    showPaperCancel: false,
    showRushModal: false,
    showPhoneEdit: false,
    newPhone: "",
    phoneChangeCode: "",
    phoneCodeSending: false,
    nickNameDirty: false,
    uploadingImageCount: 0,
    editAvatar: "",
    editAvatarUrl: "",
    politicsOptions: POLITICS,
    gradeOptions: GRADES
  },

  onLoad(query) {
    this.setData({ entryMode: query.mode || "main" });
    this.enterMode(query.mode || "main", query.id || "");
    this.loadProfile();
  },

  async loadProfile() {
    try {
      const result = await dataService.getUserProfile();
      this.setData({
        profile: Object.assign({}, this.data.profile, {
          realName: result.profile.name,
          nickName: result.info.nickName,
          no: result.profile.no || this.data.profile.no,
          title: result.profile.title || this.data.profile.title,
          activities: result.profile.activities,
          hours: result.profile.hours,
          points: result.profile.points,
          realNameVerified: result.profile.realNameVerified,
          volunteerCard: result.profile.volunteerCard
        }),
        info: Object.assign({}, this.data.info, result.info),
        editAvatarUrl: result.info.avatar || this.data.editAvatarUrl
      });
    } catch (error) {
      // 后端暂不可用时保留静态资料，避免影响页面视觉审查。
    }
  },

  enterMode(mode, id) {
    const titleMap = {
      main: "我的",
      basic: "基本信息",
      volunteerCard: "志愿者证",
      edit: "资料修改",
      certList: "协会证书",
      certDetail: "证书详情",
      paperApply: "申请纸质版",
      addressFill: "填写收货信息",
      paperManage: "申请管理",
      orderDetail: "订单详情",
      paperRecord: "订单详情"
    };
    const selectedCertificate = this.data.certificates.find((item) => item.id === id) || this.data.certificates[0];
    this.setData({
      mode,
      navTitle: titleMap[mode] || "我的",
      selectedCertificate
    });
  },

  goBack() {
    if (this.data.mode !== "main") {
      if (this.data.entryMode !== "main") {
        const pages = getCurrentPages();
        if (pages.length > 1) {
          wx.navigateBack();
          return;
        }
        wx.switchTab({ url: "/pages/mine/index" });
        return;
      }
      this.enterMode("main");
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/mine/index" });
  },

  openBasic() {
    this.enterMode("basic");
  },

  openVolunteerCard() {
    this.enterMode("volunteerCard");
  },

  openEdit() {
    this.enterMode("edit");
  },

  openCertificateList() {
    this.enterMode("certList");
  },

  openCertificateDetail(event) {
    const id = event.currentTarget.dataset.id;
    this.enterMode("certDetail", id);
  },

  openPaperRecord() {
    this.enterMode("paperManage");
  },

  updateInfoField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    const patch = { [`info.${field}`]: event.detail.value };
    // 昵称只在用户真正编辑过时才提交（避免把微信昵称默认值回写、撞别人的唯一昵称）
    if (field === "nickName") patch.nickNameDirty = true;
    this.setData(patch);
  },

  // 顶层字段输入（手机号改绑用 newPhone / phoneChangeCode）
  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: event.detail.value });
  },

  // 选头像 → 先本地预览，再真实上传换成可访问 URL（不能直接把临时路径存后端）
  chooseAvatar() {
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
        const tempFilePath = file && file.tempFilePath;
        if (!tempFilePath) return;
        // 临时路径仅用于证件照编辑框预览；info.avatar（提交字段）只在上传成功后写入，绝不写临时路径
        this.setData({ editAvatarUrl: tempFilePath, uploadingImageCount: this.data.uploadingImageCount + 1 });
        wx.showLoading({ title: "上传中", mask: true });
        dataService.uploadProfileImage(tempFilePath).then((url) => {
          this.setData({ editAvatarUrl: url, "info.avatar": url });
        }).catch((e) => {
          this.setData({ editAvatarUrl: this.data.info.avatar || "" });
          wx.showToast({ title: (e && e.message) || "头像上传失败", icon: "none" });
        }).then(() => {
          this.setData({ uploadingImageCount: Math.max(0, this.data.uploadingImageCount - 1) });
          wx.hideLoading();
        });
      }
    });
  },

  // 选 i志愿者码图片 → 真实上传换 URL（存到 ivcode 目录）。框内只显示「+」不预览，故不写临时路径；
  // info.volunteerCode（提交字段）仅在上传成功后写入，避免上传中点保存把临时路径提交到后端。
  chooseVolunteerCode() {
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
        const tempFilePath = file && file.tempFilePath;
        if (!tempFilePath) return;
        this.setData({ uploadingImageCount: this.data.uploadingImageCount + 1 });
        wx.showLoading({ title: "上传中", mask: true });
        dataService.uploadVolunteerCode(tempFilePath).then((url) => {
          this.setData({ "info.volunteerCode": url });
        }).catch((e) => {
          wx.showToast({ title: (e && e.message) || "上传失败", icon: "none" });
        }).then(() => {
          this.setData({ uploadingImageCount: Math.max(0, this.data.uploadingImageCount - 1) });
          wx.hideLoading();
        });
      }
    });
  },

  // 通讯地址：点右侧箭头调起微信地图选点（与注册页同款），回填地址文本；仍可在输入框手动改
  chooseAddressLocation() {
    if (!wx.chooseLocation) {
      wx.showToast({ title: "当前版本不支持地图选点，可直接手写地址", icon: "none" });
      return;
    }
    wx.chooseLocation({
      success: (res) => {
        const name = res.name || "";
        const address = res.address || "";
        const locationText = address && name && address.includes(name)
          ? address
          : [address, name].filter(Boolean).join(" ");
        this.setData({ "info.address": locationText });
      },
      fail: (err) => {
        if (err && /cancel/i.test(err.errMsg || "")) return;
        wx.showToast({ title: "地图选点失败，可直接手写地址", icon: "none" });
      }
    });
  },

  // ---------- 手机号改绑（需短信验证，scene=change-phone）----------
  openPhoneEdit() {
    this.setData({ showPhoneEdit: true, newPhone: "", phoneChangeCode: "" });
  },

  closePhoneEdit() {
    this.setData({ showPhoneEdit: false, newPhone: "", phoneChangeCode: "" });
  },

  async getPhoneChangeCode() {
    const phone = (this.data.newPhone || "").trim();
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: "请输入正确的新手机号", icon: "none" });
      return;
    }
    this.setData({ phoneCodeSending: true });
    try {
      await request({
        url: ENDPOINTS.volunteer.auth.smsCodes,
        method: "POST",
        data: { phone, scene: "change-phone" }
      });
      wx.showToast({ title: "验证码已发送", icon: "none" });
    } catch (error) {
      wx.showToast({ title: (error && error.message) || "验证码发送失败", icon: "none" });
    } finally {
      this.setData({ phoneCodeSending: false });
    }
  },

  async confirmPhoneChange() {
    const phone = (this.data.newPhone || "").trim();
    const smsCode = (this.data.phoneChangeCode || "").trim();
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: "请输入正确的新手机号", icon: "none" });
      return;
    }
    if (!smsCode) {
      wx.showToast({ title: "请输入验证码", icon: "none" });
      return;
    }
    try {
      await dataService.changePhone({ phone, smsCode });
      wx.showToast({ title: "手机号已更新", icon: "none" });
      this.setData({ showPhoneEdit: false, newPhone: "", phoneChangeCode: "", "info.phone": phone });
      this.loadProfile();
    } catch (error) {
      wx.showToast({ title: (error && error.message) || "手机号修改失败", icon: "none" });
    }
  },

  openPoliticsPicker() {
    this.setData({ showPoliticsPicker: true });
  },

  openGradePicker() {
    this.setData({ showGradePicker: true });
  },

  closePoliticsPicker() {
    this.setData({ showPoliticsPicker: false });
  },

  closeGradePicker() {
    this.setData({ showGradePicker: false });
  },

  closeAllSheets() {
    this.setData({
      showPoliticsPicker: false,
      showGradePicker: false,
      showPaperCancel: false,
      showRushModal: false,
      showPhoneEdit: false
    });
  },

  pickPolitics(event) {
    const index = Number(event.currentTarget.dataset.index);
    this.setData({
      "info.politics": POLITICS[index]
    });
  },

  pickGrade(event) {
    const index = Number(event.currentTarget.dataset.index);
    this.setData({
      "info.grade": GRADES[index]
    });
  },

  confirmPolitics() {
    this.closePoliticsPicker();
  },

  confirmGrade() {
    this.closeGradePicker();
  },

  saveEdit() {
    if (this.data.uploadingImageCount > 0) {
      wx.showToast({ title: "图片上传中，请稍候", icon: "none" });
      return;
    }
    if (!this.data.editAvatarUrl) {
      wx.showToast({ title: "请先上传一寸证件照", icon: "none" });
      return;
    }
    this.setData({
      "profile.volunteerCard": true
    });
    wx.showToast({ title: "已保存", icon: "none" });
    this.enterMode("basic");
  },

  async saveBasic() {
    if (this.data.uploadingImageCount > 0) {
      wx.showToast({ title: "图片上传中，请稍候", icon: "none" });
      return;
    }
    const politicsIdx = POLITICS.indexOf(this.data.info.politics);
    const gradeIdx = GRADES.indexOf(this.data.info.grade);
    // 字段名/类型对齐后端 MyProfileUpdateDTO（PATCH /v/user/profile，部分更新）。
    // GRADES 为倒序展示（毕业在前），后端 Grade code 正序，故 code = 18 - index；找不到则不传（不改）。
    // 手机号改绑需短信验证，走独立流程不在此提交；姓名/身份证/昵称不可改（xlsx Row25「其他均不可以修改」）。
    // 头像、i志愿者码支持上传修改：均传已上传的 URL（上传时已换成可访问地址）。
    const payload = {
      avatarUrl: this.data.info.avatar || "",
      school: this.data.info.school,
      address: this.data.info.address
    };
    if (politicsIdx >= 0) payload.politicalStatus = politicsIdx + 1;
    if (gradeIdx >= 0) payload.grade = 18 - gradeIdx;
    if (this.data.info.emergencyPhone) payload.emergencyContactPhone = this.data.info.emergencyPhone;
    if (this.data.info.volunteerCode) payload.iVolunteerCodeUrl = this.data.info.volunteerCode;
    // 昵称仅在用户改过时才提交（全局唯一，重名后端拒绝）
    if (this.data.nickNameDirty && this.data.info.nickName && this.data.info.nickName.trim()) {
      payload.nickName = this.data.info.nickName.trim();
    }
    try {
      await dataService.updateUserProfile(payload);
      wx.showToast({ title: "已修改", icon: "none" });
      this.setData({ nickNameDirty: false });
      this.loadProfile();
    } catch (error) {
      wx.showToast({ title: error.message || "资料接口暂不可用", icon: "none" });
    }
  },

  searchCertificate(event) {
    const searchKeyword = (event.detail.value || "").trim();
    const filteredCertificates = this.data.certificates.filter((item) => {
      return [item.title, item.date, item.no, item.type, item.object || ""].some((value) => String(value).includes(searchKeyword));
    });
    this.setData({ searchKeyword, filteredCertificates });
  },

  downloadPDF() {
    wx.showToast({ title: "下载PDF", icon: "none" });
  },

  applyPaper() {
    this.enterMode("paperApply");
  },

  openAddressFill() {
    this.enterMode("addressFill");
  },

  openOrderDetail() {
    this.enterMode("orderDetail");
  },

  selectDelivery(event) {
    this.setData({ selectedDelivery: event.currentTarget.dataset.type });
  },

  togglePaperCertificate(event) {
    const id = event.currentTarget.dataset.id;
    const certificates = this.data.certificates.map((item) => {
      if (item.id !== id) return item;
      return Object.assign({}, item, { selected: !item.selected });
    });
    const selectedPaperCertificates = certificates.filter((item) => item.selected);
    this.setData({
      certificates,
      filteredCertificates: certificates,
      selectedPaperCertificates,
      paperApplyCount: selectedPaperCertificates.length
    });
  },

  submitPaperApply() {
    this.enterMode("addressFill");
  },

  submitAddress() {
    wx.showToast({ title: "提交并支付 ￥22", icon: "none" });
    this.enterMode("paperManage");
  },

  cancelPaper() {
    this.setData({ showPaperCancel: true });
  },

  rushPaper() {
    this.setData({ showRushModal: true });
  },

  confirmPaperCancel() {
    this.setData({ showPaperCancel: false, showRushModal: false });
  },

  cancelRush() {
    this.setData({ showRushModal: false });
  },

  copyPaperCode() {
    wx.setClipboardData({
      data: this.data.paperRecord.pickupCode,
      success: () => wx.showToast({ title: "已复制", icon: "none" })
    });
  },

  toggleMainAction(event) {
    const action = event.currentTarget.dataset.action;
    if (action === "basic") this.openBasic();
    if (action === "card") this.openVolunteerCard();
    if (action === "cert") this.openCertificateList();
    if (action === "group") wx.navigateTo({ url: "/pages/mine/my-squad" });
  }
});
