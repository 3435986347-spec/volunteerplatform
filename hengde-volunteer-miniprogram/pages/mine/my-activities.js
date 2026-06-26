const dataService = require("../../utils/data-service");

const TABS = ["全部", "待录取", "待开展", "开展中", "已结束"];

const STATUS_META = {
  "待录取": "pending-admit",
  "待开展": "pending-start",
  "活动中": "in-progress",
  "开展中": "in-progress",
  "已结束": "finished"
};

const ACTION_ICONS = {
  cancel: "/assets/mine-v2/activity-cancel.png",
  checkin: "/assets/mine-v2/activity-checkin.png",
  checkout: "/assets/mine-v2/activity-checkout.png"
};

// 负责人出示的签到/签退二维码内容前缀（须与后端 constant/AttendanceQr 一致）
const CHECKIN_QR_PREFIX = "hengde-activity-checkin:";
const CHECKOUT_QR_PREFIX = "hengde-activity-checkout:";

const ACTION_BUTTONS = [
  { text: "活动详情", action: "detail" },
  { text: "联系负责人", action: "contact" },
  { text: "取消活动", action: "cancel" },
  { text: "活动签到", action: "checkin" },
  { text: "活动签退", action: "checkout" },
  { text: "紧急上报", action: "report", danger: true },
  { text: "确认到家", action: "home" },
  { text: "活动投诉", action: "complaint" },
  { text: "上传照片", action: "upload" },
  { text: "活动评价", action: "evaluate" }
];

function formatDateTime(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 16);
}

function minutesText(value) {
  const minutes = Number(value || 0);
  if (!minutes) return "0分钟";
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (!hours) return `${rest}分钟`;
  return rest ? `${hours}小时${rest}分钟` : `${hours}小时`;
}

// 是否已过某时间点（与首页 displayStatus 判「已结束」同口径用）
function isPast(value) {
  if (!value) return false;
  const t = Date.parse(String(value).replace("T", " ").replace(/-/g, "/"));
  return !Number.isNaN(t) && t < Date.now();
}

// 与首页 displayStatus 统一口径派生「我的活动」分栏，避免首页显示已结束、这里却显示待开展。
function tabOf(activity) {
  // 已结束 = 现场已结束(runStatus=2) 或已过活动结束时间 或本人已签退
  if (Number(activity.runStatus) === 2 || activity.checkOutTime || isPast(activity.endTime)) {
    return "已结束";
  }
  // 开展中 = 现场进行中(runStatus=1) 或本人已签到未签退 或当前可签到
  if (Number(activity.runStatus) === 1 || (activity.checkInTime && !activity.checkOutTime) || activity.canCheckIn) {
    return "开展中";
  }
  return "待开展";
}

function buildMyActivity(row) {
  const tab = tabOf(row);
  const checkedOut = Boolean(row.checkOutTime);
  const serviceMinutes = row.serviceMinutes || 0;
  return Object.assign({}, row, {
    id: row.id || row.activityId,
    title: row.title || row.activityTitle || "未命名活动",
    timeText: row.slotStartTime && row.slotEndTime
      ? `${formatDateTime(row.slotStartTime)} / ${formatDateTime(row.slotEndTime)}`
      : row.startTime && row.endTime
        ? `${formatDateTime(row.startTime)} / ${formatDateTime(row.endTime)}`
        : row.time || "",
    address: row.place || row.location || row.address || "",
    postName: row.projectName || "志愿者",
    signupTime: formatDateTime(row.enrollTime || row.signupTime),
    attendanceDuration: minutesText(serviceMinutes),
    recordPoints: row.pointsAward === "" || row.pointsAward === null ? "待发放" : `${row.pointsAward}积分`,
    tab,
    statusText: checkedOut ? "已结束" : tab,
    statusClass: STATUS_META[checkedOut ? "已结束" : tab] || STATUS_META["待开展"],
    actionType: row.canCheckIn || (!row.checkInTime && tab === "开展中") ? "checkin" : "detail",
    actionText: row.canCheckIn || (!row.checkInTime && tab === "开展中") ? "待签到" : "查看",
    actionIcon: row.canCheckIn || (!row.checkInTime && tab === "开展中") ? ACTION_ICONS.checkin : "",
    finishInfoType: checkedOut ? "score" : "",
    serviceDuration: minutesText(serviceMinutes),
    pointsAward: row.pointsAward ? `+${row.pointsAward}` : "待发放",
    detailMode: row.checkInTime || row.checkOutTime ? "attendance" : "none",
    canCancel: tab === "待开展",
    canCheckIn: row.canCheckIn || (!row.checkInTime && tab === "开展中"),
    canCheckOut: Boolean(row.checkInTime && !row.checkOutTime),
    canConfirmHome: Boolean(row.canConfirmHome),
    canContactLeader: Boolean(row.leaderPhone || row.contactPhone),
    canLeave: tab !== "已结束",
    canComplaint: true,
    canUpload: tab === "已结束",
    canEvaluate: Boolean(row.canReview || tab === "已结束"),
    contactPhone: row.leaderPhone || row.contactPhone || "",
    checkInMethod: row.checkInMethod || "",
    checkInTimeText: formatDateTime(row.checkInTime),
    checkOutTimeText: formatDateTime(row.checkOutTime)
  });
}

// 详情操作按钮按所选活动状态过滤（否则「活动签退」等会对未签到/已签退/未开始活动也显示）
function buildDetailActions(activity) {
  const visible = {
    detail: true,
    contact: !!activity.canContactLeader,
    cancel: !!activity.canCancel,
    checkin: !!activity.canCheckIn,
    checkout: !!activity.canCheckOut,
    report: true,
    home: !!activity.canConfirmHome,
    complaint: !!activity.canComplaint,
    upload: !!activity.canUpload,
    evaluate: !!activity.canEvaluate
  };
  return ACTION_BUTTONS.filter((btn) => visible[btn.action]);
}

function currentLocation() {
  return new Promise((resolve, reject) => {
    if (!wx.getLocation) {
      reject(new Error("当前版本不支持定位"));
      return;
    }
    wx.getLocation({
      type: "gcj02",
      success: resolve,
      fail: reject
    });
  });
}

Page({
  data: {
    mode: "list",
    navTitle: "我的活动",
    tabs: TABS,
    activeTab: 0,
    activities: [],
    filteredActivities: [],
    selectedActivity: {},
    detailActionButtons: ACTION_BUTTONS,
    showConfirmModal: false,
    confirmTitle: "",
    confirmAction: "",
    formTitle: "",
    formField: "",
    leaveText: "",
    complaintText: "",
    complaintTargets: [
      { id: 1, target: "", content: "" }
    ],
    uploadedImageCount: 0,
    uploadedVideoCount: 0,
    evaluateText: "",
    syncCommunity: true,
    returnTarget: ""
  },

  onLoad(query = {}) {
    const fromLeader = query.fromLeader === "1" || wx.getStorageSync("activityLeaderReturnDetail") === "1";
    if (fromLeader && ["upload", "evaluate"].includes(query.mode)) {
      this.setData({
        mode: query.mode,
        navTitle: query.mode === "upload" ? "照片上传" : "活动评价",
        formTitle: query.mode === "upload" ? "照片上传" : "活动评价",
        formField: query.mode === "evaluate" ? "evaluateText" : "",
        returnTarget: "activityLeaderDetail"
      });
    }
    this.loadActivities();
  },

  async loadActivities() {
    try {
      const activities = (await dataService.listMyActivities()).map(buildMyActivity);
      // 用当前 id 找 fresh row 替换 selectedActivity（否则签退/签到成功重拉后详情仍是旧 checkOut/InTime、按钮残留）
      const prevId = this.data.selectedActivity.id;
      const selectedActivity = (prevId && activities.find((item) => item.id === prevId)) || activities[0] || {};
      this.setData({
        activities,
        selectedActivity,
        detailActionButtons: buildDetailActions(selectedActivity)
      }, () => this.applyFilter());
    } catch (error) {
      wx.showToast({ title: "我的活动暂不可用", icon: "none" });
    }
  },

  applyFilter() {
    const filteredActivities = this.data.activeTab === 0
      ? this.data.activities
      : this.data.activities.filter((item) => item.tab === TABS[this.data.activeTab]);
    this.setData({ filteredActivities });
  },

  goBack() {
    if (this.data.showConfirmModal) {
      this.closeConfirmDialog();
      return;
    }
    if (this.data.returnTarget === "activityLeaderDetail") {
      wx.removeStorageSync("activityLeaderReturnDetail");
      wx.redirectTo({ url: "/pages/activity-leader/detail?id=43683435684" });
      return;
    }
    if (["leave", "complaint", "upload", "evaluate"].includes(this.data.mode)) {
      this.setData({ mode: "detail", navTitle: "我的活动" });
      return;
    }
    if (this.data.mode === "detail") {
      this.setData({ mode: "list", navTitle: "我的活动" });
      return;
    }
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.switchTab({ url: "/pages/mine/index" });
  },

  switchTab(event) {
    const activeTab = Number(event.currentTarget.dataset.index);
    this.setData({ activeTab }, () => this.applyFilter());
  },

  openDetail(event) {
    const id = event.currentTarget.dataset.id;
    const selectedActivity = this.data.activities.find((item) => item.id === id) || this.data.activities[0];
    this.setData({
      mode: "detail",
      navTitle: "我的活动",
      selectedActivity,
      detailActionButtons: buildDetailActions(selectedActivity || {})
    });
  },

  handleListAction(event) {
    const type = event.currentTarget.dataset.type;
    const id = event.currentTarget.dataset.id;
    const selectedActivity = this.data.activities.find((item) => item.id === id) || this.data.activities[0];
    this.setData({ selectedActivity, detailActionButtons: buildDetailActions(selectedActivity || {}) });

    if (type === "cancel") {
      this.cancelActivity();
      return;
    }
    if (type === "checkin") {
      this.checkIn();
      return;
    }
    if (type === "checkout") {
      this.checkOut();
      return;
    }
    this.setData({
      mode: "detail",
      navTitle: "我的活动",
      selectedActivity
    });
  },

  openActivityDetail() {
    wx.navigateTo({ url: `/pages/activity/detail?id=${this.data.selectedActivity.id}` });
  },

  cancelActivity() {
    this.openConfirmDialog("取消活动", "cancel");
  },

  confirmCancel() {
    // 活动未开展，系统规定可以取消报名时，可以无需申请即可取消活动，否则只能请假才能无责不参加。
    this.closeConfirmDialog();
    dataService.cancelEnrollActivity(this.data.selectedActivity.id)
      .then(() => {
        wx.showToast({ title: "已取消", icon: "none" });
        this.loadActivities();
      })
      .catch(() => wx.showToast({ title: "取消失败", icon: "none" }));
  },

  cancelCancel() {
    this.closeConfirmDialog();
  },

  // 自助签到：扫描负责人出示的「活动签到二维码」(内容 hengde-activity-checkin:{activityId}) + 上报当前 GPS。
  // 先校验扫到的码确属本活动，再取定位调后端——后端按 Haversine 距活动坐标 ≤ 签到半径(默认500m) + 时间窗 + 报名校验放行。
  // method=1 扫码签到；签退由负责人在管理端统一操作。
  // 注：开发者工具无摄像头，wx.scanCode 会提示「上传二维码图片」，真机上是打开摄像头扫码，属正常表现。
  async checkIn() {
    const activity = this.data.selectedActivity || {};
    if (!activity.id) return;
    if (!wx.scanCode) {
      wx.showToast({ title: "当前版本不支持扫码", icon: "none" });
      return;
    }
    wx.scanCode({
      success: async (scanResult) => {
        const content = (scanResult && scanResult.result) || "";
        if (content !== CHECKIN_QR_PREFIX + activity.id) {
          wx.showToast({ title: "请扫描本活动的签到二维码", icon: "none" });
          return;
        }
        wx.showLoading({ title: "定位中", mask: true });
        try {
          const location = await currentLocation();
          await dataService.checkInActivity(activity.id, {
            lat: location.latitude,
            lng: location.longitude,
            method: 1
          });
          wx.hideLoading();
          wx.showToast({ title: "签到成功", icon: "none" });
          this.loadActivities();
        } catch (error) {
          wx.hideLoading();
          wx.showToast({ title: error.message || "签到失败，请确认已到现场并允许定位", icon: "none" });
        }
      },
      fail: (err) => {
        if (err && /cancel/i.test(err.errMsg || "")) return;
        wx.showToast({ title: "扫码失败", icon: "none" });
      }
    });
  },

  // 自助签退：扫描负责人「活动签退二维码」(内容 hengde-activity-checkout:{activityId}) + 上报当前 GPS。
  // 校验码确属本活动后取定位调后端——后端按 Haversine 距活动 ≤ 半径 + 结束后2h内放行、算服务时长(签退−签到)。
  async checkOut() {
    const activity = this.data.selectedActivity || {};
    if (!activity.id) return;
    if (!wx.scanCode) {
      wx.showToast({ title: "当前版本不支持扫码", icon: "none" });
      return;
    }
    wx.scanCode({
      success: async (scanResult) => {
        const content = (scanResult && scanResult.result) || "";
        if (content !== CHECKOUT_QR_PREFIX + activity.id) {
          wx.showToast({ title: "请扫描本活动的签退二维码", icon: "none" });
          return;
        }
        wx.showLoading({ title: "定位中", mask: true });
        try {
          const location = await currentLocation();
          await dataService.checkOutActivity(activity.id, {
            lat: location.latitude,
            lng: location.longitude,
            method: 1
          });
          wx.hideLoading();
          wx.showToast({ title: "签退成功", icon: "none" });
          this.loadActivities();
        } catch (error) {
          wx.hideLoading();
          wx.showToast({ title: error.message || "签退失败，请确认已到现场并允许定位", icon: "none" });
        }
      },
      fail: (err) => {
        if (err && /cancel/i.test(err.errMsg || "")) return;
        wx.showToast({ title: "扫码失败", icon: "none" });
      }
    });
  },

  emergencyReport() {
    wx.makePhoneCall({ phoneNumber: this.data.selectedActivity.contactPhone || "15766508094" });
  },

  async confirmHome() {
    this.openConfirmDialog("确认到家", "home");
  },

  contactLeader() {
    wx.makePhoneCall({ phoneNumber: this.data.selectedActivity.contactPhone });
  },

  goLeave() {
    this.setData({ mode: "leave", navTitle: "申请请假", formTitle: "申请请假", formField: "leaveText" });
  },

  goComplaint() {
    // 投诉对象包括：本次活动、负责人、志愿者；志愿者对象显示单独名字。
    this.setData({ mode: "complaint", navTitle: "活动投诉", formTitle: "活动投诉", formField: "complaintText" });
  },

  goUpload() {
    this.setData({ mode: "upload", navTitle: "照片上传", formTitle: "照片上传" });
  },

  goEvaluate() {
    this.setData({ mode: "evaluate", navTitle: "活动评价", formTitle: "活动评价", formField: "evaluateText" });
  },

  handleDetailAction(event) {
    const action = event.currentTarget.dataset.action;
    if (action === "detail") return this.openActivityDetail();
    if (action === "contact") return this.contactLeader();
    if (action === "cancel") return this.cancelActivity();
    if (action === "checkin") return this.checkIn();
    if (action === "checkout") return this.checkOut();
    if (action === "report") return this.emergencyReport();
    if (action === "home") return this.confirmHome();
    if (action === "complaint") return this.goComplaint();
    if (action === "upload") return this.goUpload();
    if (action === "evaluate") return this.goEvaluate();
  },

  openConfirmDialog(title, action) {
    this.setData({
      showConfirmModal: true,
      confirmTitle: title,
      confirmAction: action
    });
  },

  closeConfirmDialog() {
    this.setData({
      showConfirmModal: false,
      confirmTitle: "",
      confirmAction: ""
    });
  },

  confirmDialogYes() {
    if (this.data.confirmAction === "cancel") {
      this.confirmCancel();
      return;
    }
    if (this.data.confirmAction === "home") {
      this.closeConfirmDialog();
      currentLocation()
        .then((location) => dataService.confirmHomeActivity(this.data.selectedActivity.id, {
          lat: location.latitude,
          lng: location.longitude
        }))
        .then(() => {
          wx.showToast({ title: "已确认到家", icon: "none" });
          this.loadActivities();
        })
        .catch((error) => wx.showToast({ title: error.message || "确认失败", icon: "none" }));
    }
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [field]: event.detail.value });
  },

  addComplaintTarget() {
    const nextId = Date.now();
    this.setData({
      complaintTargets: this.data.complaintTargets.concat([{ id: nextId, target: "", content: "" }])
    });
  },

  removeComplaintTarget(event) {
    if (this.data.complaintTargets.length <= 1) {
      wx.showToast({ title: "至少保留一项", icon: "none" });
      return;
    }
    const index = Number(event.currentTarget.dataset.index);
    this.setData({
      complaintTargets: this.data.complaintTargets.filter((item, itemIndex) => itemIndex !== index)
    });
  },

  updateComplaintTarget(event) {
    const index = Number(event.currentTarget.dataset.index);
    const field = event.currentTarget.dataset.field;
    const complaintTargets = this.data.complaintTargets.map((item, itemIndex) => {
      if (itemIndex !== index) return item;
      return Object.assign({}, item, { [field]: event.detail.value });
    });
    this.setData({ complaintTargets });
  },

  chooseUploadImage() {
    wx.chooseMedia({
      count: 9,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: (result) => {
        this.setData({ uploadedImageCount: (result.tempFiles || []).length });
        wx.showToast({ title: "已选择图片", icon: "none" });
      }
    });
  },

  chooseUploadVideo() {
    wx.chooseMedia({
      count: 1,
      mediaType: ["video"],
      sourceType: ["album", "camera"],
      success: (result) => {
        this.setData({ uploadedVideoCount: (result.tempFiles || []).length });
        wx.showToast({ title: "已选择视频", icon: "none" });
      }
    });
  },

  toggleSyncCommunity(event) {
    const values = event && event.detail && event.detail.value;
    this.setData({
      syncCommunity: Array.isArray(values) ? !!values.length : !this.data.syncCommunity
    });
  },

  async submitForm() {
    if (this.data.mode === "evaluate") {
      try {
        await dataService.reviewActivity(this.data.selectedActivity.id, {
          activityScore: 5,
          leaderScore: 5,
          comment: this.data.evaluateText || "满意"
        });
        wx.showToast({ title: "评价已提交", icon: "none" });
      } catch (error) {
        wx.showToast({ title: error.message || "评价失败", icon: "none" });
      }
    } else if (this.data.mode === "upload") {
      wx.showToast({ title: "活动相册后端暂未提供", icon: "none" });
    } else if (this.data.mode === "leave" || this.data.mode === "complaint") {
      wx.showToast({ title: "该提交接口后端暂未提供", icon: "none" });
    }
    if (this.data.returnTarget === "activityLeaderDetail") {
      return;
    }
    this.setData({ mode: "detail", navTitle: "我的活动" });
  }
});
