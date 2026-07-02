const dataService = require("../../utils/data-service");

const DEFAULT_PERSON = {
  name: "",
  phone: "",
  school: ""
};

const CLASSMATES = [];

const SESSION_GROUPS = [
  {
    date: "2025年02月03日",
    items: [
      { id: "20250203-am", dateShort: "02月03日", time: "9:00-12:00", post: "志愿者", joined: 1, quota: 20, status: "招募中", selectable: true },
      { id: "20250203-am-full", dateShort: "02月03日", time: "9:00-12:00", post: "志愿者", joined: 20, quota: 20, status: "已满员", selectable: false },
      { id: "20250203-am-wait", dateShort: "02月03日", time: "9:00-12:00", post: "志愿者", joined: 1, quota: 20, status: "未开始", selectable: false }
    ]
  },
  {
    date: "2025年02月04日",
    items: [
      { id: "20250204-am", dateShort: "02月04日", time: "9:00-12:00", post: "志愿者", joined: 1, quota: 20, status: "招募中", selectable: true },
      { id: "20250204-noon", dateShort: "02月04日", time: "12:00-14:00", post: "志愿者", joined: 1, quota: 20, status: "招募中", selectable: true }
    ]
  }
];

function formatDateRange(activity) {
  if (activity.startTime && activity.endTime) {
    const startDate = String(activity.startTime).replace("T", " ").slice(0, 10).replace(/-/g, "/");
    const endDate = String(activity.endTime).replace("T", " ").slice(5, 10).replace("-", "/");
    return `${startDate} 至 ${endDate}`;
  }
  return activity.date ? `${String(activity.date).replace(/-/g, "/")} ${activity.time || ""}` : "2025/02/03 至 03/03";
}

function dateTitle(date) {
  if (!date) return "活动场次";
  const parts = String(date).split("-");
  if (parts.length === 3) {
    return `${parts[0]}年${parts[1]}月${parts[2]}日`;
  }
  return date;
}

function buildSessionGroups(activity) {
  const slots = Array.isArray(activity.slots) ? activity.slots : [];
  if (!slots.length) {
    return SESSION_GROUPS;
  }
  const groups = {};
  slots.forEach((slot, index) => {
    const quota = Number(slot.quota || 0);
    const joined = Number(slot.joined || 0);
    const full = quota > 0 && joined >= quota;
    const rawStatus = slot.status || "";
    const selectable = !full && !["已满员", "未开始"].includes(rawStatus);
    const date = slot.date || activity.date || "";
    const groupKey = date || `group-${index}`;
    if (!groups[groupKey]) {
      groups[groupKey] = {
        date: dateTitle(date),
        items: []
      };
    }
    groups[groupKey].items.push({
      id: String(slot.id || `slot-${index}`),
      dateShort: slot.dateShort || "活动日期",
      time: slot.time || activity.time || "",
      post: slot.name || "志愿者",
      joined,
      quota,
      status: full ? "已满员" : rawStatus || "招募中",
      selectable
    });
  });
  return Object.keys(groups).map((key) => groups[key]);
}

function withSelection(groups, selectedIds) {
  return groups.map((group) => ({
    ...group,
    items: group.items.map((item) => ({
      ...item,
      checked: selectedIds.includes(item.id)
    }))
  }));
}

function selectedSessions(groups, selectedIds) {
  return groups.reduce((rows, group) => {
    group.items.forEach((item) => {
      if (selectedIds.includes(item.id)) {
        rows.push(item);
      }
    });
    return rows;
  }, []);
}

Page({
  data: {
    activityId: "",
    activityInfo: {
      title: "这是一个活动名称",
      dateRange: "2025/02/03 至 03/03",
      place: "雷州市西湖街道西湖新村17号"
    },
    person: DEFAULT_PERSON,
    sessionGroups: withSelection(SESSION_GROUPS, ["20250204-am"]),
    selectedSessionIds: ["20250204-am"],
    classmates: CLASSMATES,
    filteredClassmates: CLASSMATES,
    selectedClassmateId: "",
    classmateKeyword: "",
    showClassmatePopup: false,
    submitting: false
  },

  async onLoad(query) {
    const activityId = query.id || "";
    this.setData({ activityId });
    try {
      const activity = await dataService.getActivity(activityId);
      const sessionGroups = buildSessionGroups(activity);
      const firstSelectable = sessionGroups.reduce((found, group) => {
        return found || group.items.find((item) => item.selectable);
      }, null);
      const selectedSessionIds = firstSelectable ? [firstSelectable.id] : [];
      this.setData({
        activityInfo: {
          title: activity.title || activity.signupTitle || "这是一个活动名称",
          dateRange: activity.signupDateRange || formatDateRange(activity),
          place: activity.place || "雷州市西湖街道西湖新村17号"
        },
        selectedSessionIds,
        sessionGroups: withSelection(sessionGroups, selectedSessionIds)
      });
    } catch (error) {
      wx.showToast({ title: "活动信息暂不可用", icon: "none" });
    }
    this.loadClassmates();
    this.loadPerson();
  },

  // 报名人信息自动带出本人真实姓名/电话/学校（取不到则保留空占位，不阻塞报名）
  async loadPerson() {
    try {
      const profile = await dataService.getUserProfile();
      const info = (profile && profile.info) || {};
      this.setData({
        person: {
          name: info.name || "",
          phone: info.phone || "",
          school: info.school || ""
        }
      });
    } catch (error) {
      // 忽略：保留空占位
    }
  },

  async loadClassmates() {
    try {
      const groups = await dataService.listGroups();
      const activeGroup = groups.find((item) => item.status === "已通过" || item.status === "正常" || item.status === "ACTIVE") || groups[0];
      if (!activeGroup || !activeGroup.id) return;
      const members = await dataService.listGroupMembers(activeGroup.id);
      const classmates = members.map((item) => ({
        id: String(item.volunteerId || item.id || item.userId || ""),
        name: item.realName || item.name || item.volunteerName || "",
        phone: item.phone || item.mobile || "",
        school: item.school || item.schoolName || ""
      })).filter((item) => item.id && item.name);
      this.setData({
        classmates,
        filteredClassmates: classmates
      });
    } catch (error) {
      this.setData({
        classmates: [],
        filteredClassmates: []
      });
    }
  },

  goBack() {
    wx.navigateBack();
  },

  toggleSession(event) {
    const id = event.currentTarget.dataset.id;
    const sourceGroups = this.data.sessionGroups || [];
    const session = sourceGroups.reduce((found, group) => {
      return found || group.items.find((item) => item.id === id);
    }, null);
    const selectable = session && session.selectable;
    if (!selectable) {
      wx.showToast({ title: "该场次暂不可报名", icon: "none" });
      return;
    }
    const selected = this.data.selectedSessionIds.includes(id)
      ? this.data.selectedSessionIds.filter((item) => item !== id)
      : [...this.data.selectedSessionIds, id];
    this.setData({
      selectedSessionIds: selected,
      sessionGroups: withSelection(sourceGroups, selected)
    });
  },

  openClassmatePopup() {
    this.setData({ showClassmatePopup: true });
  },

  closeClassmatePopup() {
    this.setData({ showClassmatePopup: false });
  },

  onClassmateSearch(event) {
    const keyword = event.detail.value.trim();
    const filtered = keyword
      ? this.data.classmates.filter((item) => item.name.includes(keyword) || item.school.includes(keyword))
      : this.data.classmates;
    this.setData({
      classmateKeyword: keyword,
      filteredClassmates: filtered
    });
  },

  chooseClassmate(event) {
    this.setData({ selectedClassmateId: event.currentTarget.dataset.id });
  },

  confirmClassmate() {
    const selected = this.data.classmates.find((item) => item.id === this.data.selectedClassmateId);
    this.setData({
      person: selected || this.data.person,
      showClassmatePopup: false
    });
  },

  async submitSignup() {
    const sourceGroups = this.data.sessionGroups || [];
    const selected = selectedSessions(sourceGroups, this.data.selectedSessionIds);
    if (!selected.length) {
      wx.showToast({ title: "请选择活动场次", icon: "none" });
      return;
    }
    // slot id 须为正整数；mock/兜底场次 id（"slot-0"/"20250204-am"→NaN，""/null→0）一律拦下，避免无效 slotId 触发后端 400
    const selectedSlotIds = this.data.selectedSessionIds.map(Number);
    if (!selectedSlotIds.every((id) => Number.isInteger(id) && id > 0)) {
      wx.showToast({ title: "活动场次不可用，请刷新后重试", icon: "none" });
      return;
    }
    this.setData({ submitting: true });
    try {
      if (this.data.selectedClassmateId) {
        await dataService.proxyEnrollActivity(this.data.activityId, {
          volunteerIds: [Number(this.data.selectedClassmateId)],
          slotIds: selectedSlotIds
        });
      } else {
        await dataService.enrollActivity(this.data.activityId, {
          slotIds: selectedSlotIds
        });
      }
    } catch (error) {
      wx.showToast({ title: error.message || "报名接口暂不可用", icon: "none" });
      this.setData({ submitting: false });
      return;
    }
    wx.setStorageSync("activitySignupSuccess", {
      activityInfo: this.data.activityInfo,
      person: this.data.person,
      sessions: selected
    });
    this.setData({ submitting: false });
    wx.navigateTo({ url: "/pages/activity/signup-success" });
  }
});
