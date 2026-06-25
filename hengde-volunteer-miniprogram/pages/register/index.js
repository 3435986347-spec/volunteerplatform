const localAuthMock = require("../../utils/local-auth-mock");
const auth = require("../../utils/auth");
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

// 从 18 位大陆居民身份证算周岁年龄；非 18 位（港澳台证件等无法据此推算）返回 null。
function ageFromIdCard(idNo) {
  if (!idNo || idNo.length !== 18) return null;
  const y = Number(idNo.substring(6, 10));
  const m = Number(idNo.substring(10, 12));
  const d = Number(idNo.substring(12, 14));
  if (!y || !m || !d) return null;
  const today = new Date();
  let age = today.getFullYear() - y;
  const mm = today.getMonth() + 1;
  const dd = today.getDate();
  if (mm < m || (mm === m && dd < d)) age -= 1;
  return age;
}

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
    phoneLocked: false,
    smsCode: "",
    emergencyPhone: "",
    school: "",
    grade: "",
    gradeCode: null,
    politicalStatus: "",
    politicalStatusCode: null,
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
    this.prefillLoginPhone();
    this.loadAgreement();
  },

  // 方案A：账号 = 手机号。登录手机号已在登录时验证过，注册页直接带出、锁定不可改，
  // 也无需再发注册验证码（后端对已绑手机号的账号会跳过 REGISTER 校验）。
  // 微信登录等未绑手机号的账号取不到，phone 仍可编辑并走验证码流程。
  prefillLoginPhone() {
    const loginPhone = (auth.getUser() || {}).phone || "";
    if (/^1\d{10}$/.test(loginPhone)) {
      this.setData({ phone: loginPhone, phoneLocked: true });
    }
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
    // 显示用 label，提交用 code（下标+1，与后端 Grade 枚举 code 顺序一致）
    this.setData({ grade: GRADES[index], gradeCode: index + 1 });
  },

  onPickPolitical(event) {
    const index = Number(event.detail.value);
    // 显示用 label，提交用 code（下标+1，与后端 PoliticalStatus 枚举 code 顺序一致）
    this.setData({ politicalStatus: POLITICS[index], politicalStatusCode: index + 1 });
  },

  async sendSmsCode() {
    if (this.data.phoneLocked) {
      // 登录手机号已验证，无需再发码
      return;
    }
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

    // 紧急联系方式：身份证未满 18 岁必填；满 18 岁不强制、填不填都不提示。
    const age = ageFromIdCard(idNo);
    if (age !== null && age < 18 && !emergencyPhone) {
      wx.showToast({ title: "未成年志愿者请填写紧急联系方式", icon: "none" });
      return;
    }
    // 紧急联系方式不能与本人手机号相同（填了就校验，与需求「和手机号需要不一样」一致）
    if (emergencyPhone && emergencyPhone === phone) {
      wx.showToast({ title: "紧急联系方式不能与本人手机号相同", icon: "none" });
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

    // 字段名/类型须与后端 RegisterDTO 对齐：idCardNo / emergencyContactPhone / iVolunteerCodeUrl / signatureUrl；
    // politicalStatus、grade 后端是 Integer code（非中文 label），这里发 code，否则 Jackson 反序列化 400。
    const payload = {
      realName,
      idCardNo: idNo,
      phone,
      smsCode: this.data.smsCode,
      emergencyContactPhone: emergencyPhone,
      school,
      grade: this.data.gradeCode,
      politicalStatus: this.data.politicalStatusCode,
      address: addressText,
      iVolunteerCodeUrl: volunteerCodeImage,
      signatureUrl: agreementSignatureUrl
    };

    try {
      await dataService.registerVolunteer(payload);
      wx.showToast({ title: "注册信息已提交", icon: "success" });
      setTimeout(() => wx.switchTab({ url: "/pages/home/index" }), 700);
    } catch (error) {
      // 401=登录态丢失（注册需先验证码登录拿游客 token）；其余直接显示后端校验信息
      const title = error.statusCode === 401
        ? "请先登录后再注册"
        : (error.message || "注册失败");
      wx.showToast({ title, icon: "none" });
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
