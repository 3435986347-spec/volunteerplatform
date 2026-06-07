const dataService = require("../../utils/data-service");

const DEFAULT_COVERS = [
  "/assets/activity/detail-cover-1.png",
  "/assets/login/leizhou-hero-transparent.png"
];

const GUARANTEE_ORDER = [
  { key: "clothing", label: "志愿者服装", asset: "01-clothing" },
  { key: "water", label: "提供饮水", asset: "02-water" },
  { key: "certificate", label: "志愿服务证书", asset: "03-certificate" },
  { key: "training", label: "专项培训", asset: "04-training" },
  { key: "insurance", label: "志愿者保险", asset: "05-insurance" },
  { key: "traffic", label: "交通补贴", asset: "06-traffic" },
  { key: "meal", label: "餐饮或食物", asset: "07-meal" },
  { key: "bus", label: "集中乘车", asset: "08-bus" },
  { key: "hotel", label: "提供住宿", asset: "09-hotel" },
  { key: "tool", label: "志愿服务工具", asset: "10-tool" },
  { key: "checkup", label: "免费体检", asset: "11-checkup" },
  { key: "other", label: "其他", asset: "12-other" }
];

const DEFAULT_REGISTRANTS = [
  { id: "u001", name: "周怡汐", avatar: "/assets/icons/activity-logo.png" },
  { id: "u002", name: "曾嘉豪", avatar: "/assets/icons/activity-logo.png" },
  { id: "u003", name: "黄欣", avatar: "/assets/icons/activity-logo.png" },
  { id: "u004", name: "李朝凤", avatar: "/assets/icons/activity-logo.png" },
  { id: "u005", name: "陈凯纯", avatar: "/assets/icons/activity-logo.png" }
];

function parseDate(value) {
  if (!value) return null;
  const date = new Date(String(value).replace(/-/g, "/"));
  return Number.isNaN(date.getTime()) ? null : date;
}

function pad(value) {
  return String(value).padStart(2, "0");
}

function formatCountdown(deadline) {
  const end = parseDate(deadline);
  if (!end) return "报名截止时间待定";
  const diff = end.getTime() - Date.now();
  if (diff <= 0) return "报名已截止";
  const totalMinutes = Math.floor(diff / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  return `剩余${pad(days)}天${pad(hours)}时${pad(minutes)}分截止`;
}

function formatOpenCountdown(openAt) {
  const start = parseDate(openAt);
  if (!start) return "开放时间待定";
  const diff = start.getTime() - Date.now();
  if (diff <= 0) return "即将开放报名";
  const totalMinutes = Math.floor(diff / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  return `${pad(days)}天${pad(hours)}时${pad(minutes)}分后开放报名`;
}

function getSignupOpenAt(activity, signupRows) {
  const signupTimes = activity.signupTimes || {};
  return activity.signupStartTime
    || activity.enrollStartTime
    || activity.registrationStartTime
    || activity.applyStartTime
    || signupTimes.volunteer
    || signupTimes.leader
    || signupTimes.temporaryLeader
    || signupTimes.manager
    || signupRows && signupRows[2] && signupRows[2].value
    || "";
}

function buildSignupButton(activity, deadline, signupRows) {
  const status = activity.status || "";
  if (status === "未开放") {
    return {
      title: "未开放报名",
      subtitle: formatOpenCountdown(getSignupOpenAt(activity, signupRows)),
      disabled: true
    };
  }
  if (["报名截止", "活动中", "已结束"].includes(status)) {
    return {
      title: "报名截止",
      subtitle: "",
      disabled: true
    };
  }
  return {
    title: "我要报名",
    subtitle: formatCountdown(deadline),
    disabled: false
  };
}

function formatPeriod(slot, index) {
  if (!slot || typeof slot !== "object") {
    return { name: `时间段${index + 1}`, text: "" };
  }
  const name = slot.name || `时间段${index + 1}`;
  if (slot.text) return { name, text: slot.text };
  if (slot.time) return { name, text: slot.time };
  const start = slot.start || slot.startTime || slot.beginTime;
  const end = slot.end || slot.endTime || slot.finishTime;
  return { name, text: start && end ? `${start} 至 ${end}` : "" };
}

function normalizeRoleRows(source, fallback) {
  const data = source || {};
  return [
    { role: "管理团队", value: data.manager || data.admin || fallback.manager },
    { role: "临时负责人", value: data.leader || data.temporaryLeader || fallback.leader },
    { role: "志愿者", value: data.volunteer || fallback.volunteer }
  ];
}

function normalizePoints(source, fallback) {
  const data = source || {};
  return [
    { role: "管理团队", value: data.manager || data.admin || fallback.manager },
    { role: "临时负责人", value: data.leader || data.temporaryLeader || fallback.leader },
    { role: "志愿者", value: data.volunteer || fallback.volunteer }
  ];
}

function normalizeGuarantees(source) {
  const enabledLabels = Array.isArray(source)
    ? source.map((item) => (typeof item === "string" ? item : item.label || item.name || item.key))
    : [];
  return GUARANTEE_ORDER.map((item) => {
    const enabled = enabledLabels.includes(item.label) || enabledLabels.includes(item.key);
    return {
      ...item,
      enabled,
      iconUrl: `/assets/activity/guarantee/${item.asset}-${enabled ? "red" : "gray"}.png`
    };
  });
}

function normalizeRegistrants(source) {
  const rows = Array.isArray(source) && source.length ? source : DEFAULT_REGISTRANTS;
  return rows.map((item, index) => ({
    id: item.id || `registrant-${index}`,
    name: item.name || item.realName || "志愿者",
    avatar: item.avatar || item.avatarUrl || "/assets/icons/activity-logo.png"
  }));
}

function getDistance(lat1, lng1, lat2, lng2) {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLng = (lng2 - lng1) * rad;
  const a = Math.pow(Math.sin(dLat / 2), 2)
    + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLng / 2), 2);
  const km = 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return `${km.toFixed(2)}km`;
}

function buildViewModel(activity, distanceText) {
  const deadline = activity.enrollDeadline || activity.recruitInfo && activity.recruitInfo.deadline || "";
  const contactName = activity.contactName || String(activity.contact || "").split(" ")[0] || "联系人";
  const contactPhone = activity.contactPhone || String(activity.contact || "").split(" ")[1] || "";
  const covers = activity.coverImages && activity.coverImages.length
    ? activity.coverImages
    : [activity.coverImageUrl || activity.imageUrl || DEFAULT_COVERS[0], DEFAULT_COVERS[1]];
  const signupRows = normalizeRoleRows(activity.signupTimes, {
    manager: "2026/06/01 09:00",
    leader: "2026/06/01 12:00",
    volunteer: "2026/06/01 18:00"
  });
  const signupButton = buildSignupButton(activity, deadline, signupRows);
  const timeSlotRows = activity.timeSlots && activity.timeSlots.length
    ? activity.timeSlots
    : activity.slots && activity.slots.length
      ? activity.slots
      : [{ name: "时间段一", text: `${activity.date || ""} ${activity.time || ""}` }];
  const recruitRows = [
    { label: "报名截止时间", value: deadline || "待定" },
    { label: "报名限制", value: activity.recruitInfo && activity.recruitInfo.limit || activity.target || "全平台志愿者" },
    { label: "亲子体验", value: activity.recruitInfo && activity.recruitInfo.family || "以活动要求为准" },
    { label: "学生参与", value: activity.recruitInfo && activity.recruitInfo.student || "以活动要求为准" },
    { label: "报名要求", value: activity.recruitInfo && activity.recruitInfo.requirement || activity.summary || "报名后请按时到场并服从现场安排。" }
  ];
  if (Number(activity.requireMinJoinCount || 0) > 0) {
    recruitRows.push({ label: "已参加次数门槛", value: `${activity.requireMinJoinCount}次` });
  }
  if (Number(activity.requireMinJoinMinutes || 0) > 0) {
    recruitRows.push({ label: "已服务时长门槛", value: `${activity.requireMinJoinMinutes}分钟` });
  }

  return {
    ...activity,
    activityNo: activity.activityNo || activity.no || "",
    covers,
    distanceText: distanceText || activity.distance || "定位后显示",
    signupRows,
    pointRows: normalizePoints(activity.pointsByRole, {
      manager: activity.points || 0,
      leader: activity.points || 0,
      volunteer: activity.points || 0
    }),
    contactOrg: activity.contactOrg || activity.team || "雷州市恒德爱心公益协会",
    contactName,
    contactPhone,
    timeRows: timeSlotRows.map(formatPeriod),
    content: activity.content || activity.summary || "活动内容待发布。",
    recruitRows,
    guarantees: normalizeGuarantees(activity.guarantees),
    registrants: normalizeRegistrants(activity.registrants),
    buttonTitle: signupButton.title,
    buttonSubtitle: signupButton.subtitle,
    buttonDisabled: signupButton.disabled,
    countdownText: signupButton.subtitle
  };
}

Page({
  data: {
    activity: {},
    countdownText: "报名截止时间待定"
  },

  async onLoad(query) {
    this.activityId = query.id;
    await this.loadActivity(query.id);
  },

  onUnload() {
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer);
    }
  },

  async loadActivity(id) {
    try {
      const activity = await dataService.getActivity(id);
      const viewModel = buildViewModel(activity);
      this.setData({
        activity: viewModel,
        countdownText: viewModel.countdownText
      });
      this.refreshDistance(activity);
      this.startCountdown();
    } catch (error) {
      wx.showToast({ title: "活动详情暂不可用", icon: "none" });
    }
  },

  startCountdown() {
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer);
    }
    this.countdownTimer = setInterval(() => {
      const activity = this.data.activity;
      const deadline = activity.enrollDeadline || activity.recruitRows && activity.recruitRows[0] && activity.recruitRows[0].value;
      const signupButton = buildSignupButton(activity, deadline, activity.signupRows);
      this.setData({
        "activity.buttonTitle": signupButton.title,
        "activity.buttonSubtitle": signupButton.subtitle,
        "activity.buttonDisabled": signupButton.disabled,
        countdownText: signupButton.subtitle
      });
    }, 60000);
  },

  refreshDistance(activity) {
    if (!activity.latitude || !activity.longitude || !wx.getLocation) {
      return;
    }
    wx.getLocation({
      type: "gcj02",
      success: (res) => {
        const distanceText = getDistance(
          Number(res.latitude),
          Number(res.longitude),
          Number(activity.latitude),
          Number(activity.longitude)
        );
        this.setData({
          "activity.distanceText": distanceText
        });
      }
    });
  },

  previewImage(event) {
    const current = event.currentTarget.dataset.src;
    wx.previewImage({
      current,
      urls: this.data.activity.covers || []
    });
  },

  callContact() {
    const phone = this.data.activity.contactPhone;
    if (!phone) {
      wx.showToast({ title: "暂无联系电话", icon: "none" });
      return;
    }
    wx.makePhoneCall({ phoneNumber: phone });
  },

  viewRegistrants() {
    wx.navigateTo({ url: `/pages/activity/registrants?id=${this.data.activity.id}` });
  },

  goSignup() {
    if (this.data.activity.buttonDisabled) {
      wx.showToast({ title: this.data.activity.buttonTitle || "暂不可报名", icon: "none" });
      return;
    }
    wx.navigateTo({ url: `/pages/activity/signup?id=${this.data.activity.id}` });
  },

  onShareAppMessage() {
    const activity = this.data.activity;
    return {
      title: activity.title || "志愿活动",
      path: `/pages/activity/detail?id=${activity.id}`,
      imageUrl: activity.covers && activity.covers[0]
    };
  }
});
