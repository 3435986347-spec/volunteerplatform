const dataService = require("../../utils/data-service");
const { GUARANTEE_ORDER, guaranteeIcon } = require("../../utils/service-guarantees");

const DEFAULT_COVERS = [
  "/assets/activity/detail-cover-1.png",
  "/assets/login/leizhou-hero-transparent.png"
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

// 报名开放时间：仅读后端真实字段（不含 signupRows 的硬编码展示兜底）。无值=立即开放。
function getEnrollOpenAt(activity) {
  const signupTimes = activity.signupTimes || {};
  return activity.signupStartTime
    || activity.enrollStartTime
    || activity.registrationStartTime
    || activity.applyStartTime
    || signupTimes.volunteer
    || "";
}

// 能否报名按「真实开放/截止时间」判定，不再用 displayStatus（活动中>报名中 的优先级会把未截止的进行中活动也禁掉）。
function buildSignupButton(activity, deadline) {
  const status = activity.status || "";
  // 活动本身已结束 → 不可报名
  if (status === "已结束") {
    return { title: "活动已结束", subtitle: "", disabled: true };
  }
  const now = Date.now();
  // 未到报名开放时间（无值=立即开放）
  const openAt = parseDate(getEnrollOpenAt(activity));
  if (openAt && now < openAt.getTime()) {
    return { title: "未开放报名", subtitle: formatOpenCountdown(getEnrollOpenAt(activity)), disabled: true };
  }
  // 实际截止：留空按活动结束时间兜底（与后端 endTime 口径一致）；「活动中」但未到截止仍可报名
  const effectiveDeadline = activity.enrollDeadline || activity.endTime || deadline || "";
  const dl = parseDate(effectiveDeadline);
  if (dl && now > dl.getTime()) {
    return { title: "报名截止", subtitle: "", disabled: true };
  }
  return { title: "我要报名", subtitle: formatCountdown(effectiveDeadline), disabled: false };
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
      iconUrl: guaranteeIcon(item.asset, enabled)
    };
  });
}

function normalizeRegistrants(source) {
  // 无真实报名数据就返回空（不再用预置假人填充），由页面显示「暂无报名」空状态
  const rows = Array.isArray(source) ? source : [];
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
  const signupButton = buildSignupButton(activity, deadline);
  // 优先用已归一化的 slots（其 time 带日期，修「07:00-07:00」丢日期）；回退原始 timeSlots
  const timeSlotRows = activity.slots && activity.slots.length
    ? activity.slots
    : activity.timeSlots && activity.timeSlots.length
      ? activity.timeSlots
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
    quotaText: Number(activity.quota || 0) > 0 ? activity.quota : "不限",
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
      const signupButton = buildSignupButton(activity, deadline);
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
