const localAuthMock = require("../../utils/local-auth-mock");
const { request } = require("../../utils/request");
const { ENDPOINTS } = require("../../utils/api-endpoints");
const dataService = require("../../utils/data-service");

const ID_TYPE_LENGTHS = {
  "大陆居民身份证": 18,
  "港澳居民来往内地通行证": 9,
  "台湾居民来往内地通行证": 8
};

const ID_TYPES = Object.keys(ID_TYPE_LENGTHS);

const GRADES = [
  "一年级", "二年级", "三年级", "四年级", "五年级",
  "六年级", "七年级", "八年级", "九年级",
  "高中一年级", "高中二年级", "高中三年级",
  "大一", "大二", "大三", "大四", "大五",
  "毕业"
];

const POLITICS = ["群众", "共青团员", "中共预备党员", "中共党员", "民主党派"];

Page({
  data: {
    idTypes: ID_TYPES,
    grades: GRADES,
    politics: POLITICS,
    realName: "",
    idType: "",
    idMaxLength: 18,
    idNo: "",
    phone: "",
    smsCode: "",
    emergencyPhone: "",
    school: "",
    grade: "",
    politicalStatus: "",
    addressLocation: null,
    addressText: "",
    volunteerCodeImage: "",
    agreementTitle: "志愿者服务协议",
    agreementContent: "",
    agreementAccepted: false,
    agreementSignatureUrl: "",
    smsSending: false
  },

  onLoad() {
    this.loadAgreement();
  },

  async loadAgreement() {
    try {
      const agreement = await dataService.getAgreement();
      this.setData({
        agreementTitle: agreement.title || "志愿者服务协议",
        agreementContent: agreement.content || ""
      });
    } catch (error) {
      this.setData({
        agreementContent: "志愿者服务协议暂不可用，请稍后重试。"
      });
    }
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) {
      return;
    }

    this.setData({
      [field]: event.detail.value
    });
  },

  onPickIdType(event) {
    const index = Number(event.detail.value);
    const idType = ID_TYPES[index];
    const idMaxLength = ID_TYPE_LENGTHS[idType];
    const truncated = (this.data.idNo || "").slice(0, idMaxLength);
    this.setData({
      idType,
      idMaxLength,
      idNo: truncated
    });
  },

  onPickGrade(event) {
    const index = Number(event.detail.value);
    this.setData({ grade: GRADES[index] });
  },

  onPickPolitical(event) {
    const index = Number(event.detail.value);
    this.setData({ politicalStatus: POLITICS[index] });
  },

  async sendSmsCode() {
    const phone = (this.data.phone || "").trim();
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({
        title: "请输入正确手机号",
        icon: "none"
      });
      return;
    }

    this.setData({ smsSending: true });

    try {
      await request({
        url: ENDPOINTS.volunteer.auth.smsCodes,
        method: "POST",
        data: {
          phone
        }
      });

      wx.showToast({
        title: "验证码已发送",
        icon: "none"
      });
    } catch (error) {
      const localCode = localAuthMock.createSmsCode();
      if (localCode) {
        this.setData({ smsCode: localCode });
        wx.showToast({
          title: `Local code: ${localCode}`,
          icon: "none"
        });
        return;
      }

      wx.showToast({
        title: "验证码接口暂不可用",
        icon: "none"
      });
    } finally {
      this.setData({ smsSending: false });
    }
  },

  confirmSmsCode() {
    if (!this.data.smsCode) {
      wx.showToast({
        title: "请输入验证码",
        icon: "none"
      });
      return;
    }

    wx.showToast({
      title: "验证码已填写",
      icon: "none"
    });
  },

  chooseAddressLocation() {
    if (!wx.chooseLocation) {
      wx.showToast({
        title: "当前版本不支持地图选点，可直接手写地址",
        icon: "none"
      });
      return;
    }

    wx.chooseLocation({
      success: (res) => {
        const name = res.name || "";
        const address = res.address || "";
        const addressLocation = {
          name,
          address,
          latitude: res.latitude,
          longitude: res.longitude
        };
        const locationText = address && name && address.includes(name)
          ? address
          : [address, name].filter(Boolean).join(" ");

        this.setData({
          addressLocation,
          addressText: locationText
        });
      },
      fail: (err) => {
        if (err && /cancel/i.test(err.errMsg || "")) {
          return;
        }

        wx.showToast({
          title: "地图选点失败，可直接手写地址",
          icon: "none"
        });
      }
    });
  },

  chooseVolunteerCode() {
    this.chooseOneImage("volunteerCodeImage");
  },

  chooseSignatureImage() {
    this.chooseOneImage("agreementSignatureUrl");
  },

  chooseOneImage(field) {
    const chooseMedia = wx.chooseMedia || wx.chooseImage;
    if (!chooseMedia) {
      wx.showToast({
        title: "当前版本不支持上传图片",
        icon: "none"
      });
      return;
    }

    chooseMedia({
      count: 1,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: (res) => {
        const file = res.tempFiles ? res.tempFiles[0] : res.tempFilePaths && { tempFilePath: res.tempFilePaths[0] };
        this.setData({
          [field]: file ? file.tempFilePath : ""
        });
      }
    });
  },

  onAgreementChange(event) {
    this.setData({
      agreementAccepted: (event.detail.value || []).includes("accepted")
    });
  },

  previewAgreement() {
    wx.showModal({
      title: this.data.agreementTitle,
      content: this.data.agreementContent || "暂无协议内容",
      showCancel: false,
      confirmText: "我已阅读"
    });
  },

  async nextStep() {
    const {
      realName,
      idType,
      idNo,
      phone,
      emergencyPhone,
      school,
      grade,
      politicalStatus,
      addressText,
      volunteerCodeImage,
      agreementAccepted,
      agreementSignatureUrl
    } = this.data;

    if (!(realName || "").trim()) {
      wx.showToast({ title: "请输入姓名", icon: "none" });
      return;
    }

    if (!idType) {
      wx.showToast({ title: "请选择身份证类型", icon: "none" });
      return;
    }

    const expected = ID_TYPE_LENGTHS[idType];
    if ((idNo || "").length !== expected) {
      wx.showToast({ title: `${idType}应为 ${expected} 位`, icon: "none" });
      return;
    }

    if (!/^1\d{10}$/.test(phone || "")) {
      wx.showToast({ title: "手机号需为 11 位", icon: "none" });
      return;
    }

    if (emergencyPhone && !/^1\d{10}$/.test(emergencyPhone)) {
      wx.showToast({ title: "紧急联系手机号需为 11 位", icon: "none" });
      return;
    }

    if (!politicalStatus) {
      wx.showToast({ title: "请选择政治面貌", icon: "none" });
      return;
    }

    if (!(school || "").trim()) {
      wx.showToast({ title: "请输入学校", icon: "none" });
      return;
    }

    if (!grade) {
      wx.showToast({ title: "请选择年级", icon: "none" });
      return;
    }

    if (!(addressText || "").trim()) {
      wx.showToast({ title: "请输入通讯地址", icon: "none" });
      return;
    }

    if (!volunteerCodeImage) {
      wx.showToast({ title: "请上传i志愿者码", icon: "none" });
      return;
    }

    if (!agreementAccepted) {
      wx.showToast({ title: "请先阅读并同意协议", icon: "none" });
      return;
    }

    if (!agreementSignatureUrl) {
      wx.showToast({ title: "请上传手写签名图", icon: "none" });
      return;
    }

    const payload = {
      realName,
      idType,
      idNo,
      phone,
      smsCode: this.data.smsCode,
      emergencyPhone,
      school,
      grade,
      politicalStatus,
      address: addressText,
      addressLocation: this.data.addressLocation,
      volunteerCodeImageUrl: volunteerCodeImage,
      agreementAccepted: true,
      agreementSignatureUrl
    };

    try {
      await dataService.registerVolunteer(payload);
      wx.showToast({
        title: "注册信息已提交",
        icon: "none"
      });
    } catch (error) {
      wx.showToast({
        title: "注册接口暂不可用",
        icon: "none"
      });
    }
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
