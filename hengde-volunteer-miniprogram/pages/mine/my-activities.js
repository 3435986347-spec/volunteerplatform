const dataService = require("../../utils/data-service");

const TABS = ["全部", "待录取", "待开展", "开展中", "已结束"];

const STATUS_META = {
  "待录取": "pending-admit",
  "待开展": "pending-start",
  "活动中": "in-progress",
  "已结束": "finished"
};

const ACTION_ICONS = {
  cancel: "/assets/mine-v2/activity-cancel.png",
  checkin: "/assets/mine-v2/activity-checkin.png",
  checkout: "/assets/mine-v2/activity-checkout.png"
};

const ACTION_BUTTONS = [
  { text: "活动详情", action: "detail" },
  { text: "联系负责人", action: "contact" },
  { text: "取消活动", action: "cancel" },
  { text: "活动签到", action: "checkin" },
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

function tabOf(activity) {
  const status = String(activity.status || activity.runStatus || "");
  if (activity.status === "已结束" || status === "4" || status === "FINISHED") return "已结束";
  if (activity.checkInTime && !activity.checkOutTime) return "开展中";
  if (activity.checkInStatus || activity.attendanceStatus) return "开展中";
  if (activity.canCheckIn) return "开展中";
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
      this.setData({
        activities,
        selectedActivity: this.data.selectedActivity.id ? this.data.selectedActivity : activities[0] || {}
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
      selectedActivity
    });
  },

  handleListAction(event) {
    const type = event.currentTarget.dataset.type;
    const id = event.currentTarget.dataset.id;
    const selectedActivity = this.data.activities.find((item) => item.id === id) || this.data.activities[0];
    this.setData({ selectedActivity });

    if (type === "cancel") {
      this.cancelActivity();
      return;
    }
    if (type === "checkin") {
      this.checkIn();
      return;
    }
    if (type === "checkout") {
      wx.showToast({ title: "待签退", icon: "none" });
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

  // 【签到】方式有二维码签到、负责人签到；【签退】方式有负责人签到。
  // 自助签到表示志愿者到达活动地点后在小程序中自己签到。
  // 签到逻辑后续通过地址定位判断距离，例如 500m 或 1km。
  // 是否开启自助签到、判断距离多远等功能，都由后台创建活动时设置。
  // 【活动签到】打开扫一扫；【活动签退】由负责人在管理版块点击。
  async checkIn() {
    const activity = this.data.selectedActivity || {};
    if (!activity.id) return;
    wx.scanCode({
      success: async (scanResult) => {
        try {
          const location = await currentLocation();
          await dataService.checkInActivity(activity.id, {
            lat: location.latitude,
            lng: location.longitude,
            qrCode: scanResult.result || ""
          });
          wx.showToast({ title: "签到成功", icon: "none" });
          this.loadActivities();
        } catch (error) {
          wx.showToast({ title: error.message || "签到失败", icon: "none" });
        }
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
