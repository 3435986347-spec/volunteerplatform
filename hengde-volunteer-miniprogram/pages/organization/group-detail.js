const dataService = require("../../utils/data-service");

const TABS = ["小组成员", "小组管理", "审批申请", "小组日志"];

const MEMBERS = [
  { id: "m001", name: "黄灵媛", phone: "15766508094", school: "雷州市第二中学", role: "组长" },
  { id: "m002", name: "黄灵媛", phone: "15766508094", school: "雷州市第二中学", role: "管理员" },
  { id: "m003", name: "黄灵媛", phone: "15766508094", school: "雷州市第二中学", role: "成员" },
  { id: "m004", name: "黄灵媛", phone: "15766508094", school: "雷州市第二中学", role: "成员" }
];

const APPLICATIONS = [
  { id: "a001", name: "黄灵媛", school: "雷州市第二中学", checked: false },
  { id: "a002", name: "黄灵媛", school: "雷州市第二中学", checked: false },
  { id: "a003", name: "黄灵媛", school: "雷州市第二中学", checked: false }
];

const LOGS = [
  { id: "l001", time: "2025/03/02 12:31", actor: "邝大程", action: "通过了", target: "黄灵媛", suffix: "的小组申请" },
  { id: "l002", time: "2025/03/02 12:12", actor: "邝大程", action: "拒绝了", target: "黄灵媛", suffix: "的小组申请" },
  { id: "l003", time: "2025/03/02 11:12", actor: "邝大程", action: "帮助", target: "黄灵媛", suffix: "报名了", activityId: "act002", activityName: "雷城客运站志愿服务活动" },
  { id: "l004", time: "2025/03/02 11:12", actor: "邝大程", action: "删除了成员", target: "黄灵媛", suffix: "" },
  { id: "l005", time: "2025/03/02 11:12", actor: "邝大程", action: "设置", target: "黄灵媛", suffix: "成为了管理员" },
  { id: "l006", time: "2025/03/02 11:12", actor: "邝大程", action: "删除了管理员", target: "黄灵媛", suffix: "的权限" },
  { id: "l007", time: "2025/03/02 11:12", actor: "邝大程", action: "把组长转让给了", target: "黄灵媛", suffix: "" },
  { id: "l008", time: "2025/03/02 11:12", actor: "黄灵媛", action: "退出了本小组", target: "", suffix: "" }
];

const ACTIONS = [
  { id: "setAdmin", label: "设置管理员", roles: ["leader"], picker: true },
  { id: "removeAdmin", label: "删除管理员", roles: ["leader"], picker: true },
  { id: "removeMember", label: "删除成员", roles: ["leader", "admin"], picker: true },
  { id: "transferLeader", label: "转让组长", roles: ["leader"], picker: true },
  { id: "dissolve", label: "解散小组", roles: ["leader"] },
  { id: "quit", label: "退出小组", roles: ["leader", "admin", "member"] }
];

function displayGroup(group) {
  return {
    id: group.id || "grp001",
    no: group.no || "32022",
    name: group.name || "这是一个小组名称",
    leader: group.leader || "邝大程",
    createdAt: group.createdAt || group.createTime || "2025/08/04",
    members: group.members || group.memberCount || 320,
    intro: group.intro || "这是一个小组简介",
    logo: group.logo || "/assets/icons/activity-logo.png",
    banner: group.banner || "/assets/login/leizhou-hero-transparent.png"
  };
}

function displayMember(row, index) {
  return {
    id: String(row.memberId || row.id || row.volunteerId || `member-${index}`),
    volunteerId: String(row.volunteerId || row.id || ""),
    name: row.realName || row.name || row.volunteerName || "",
    phone: row.phone || row.mobile || "",
    school: row.school || row.schoolName || "",
    role: row.roleName || row.role || (row.leader ? "组长" : row.admin ? "管理员" : "成员")
  };
}

function displayApplication(row, index) {
  return {
    id: String(row.memberId || row.id || `application-${index}`),
    memberId: String(row.memberId || row.id || ""),
    name: row.realName || row.name || row.volunteerName || "",
    school: row.school || row.schoolName || "",
    checked: false
  };
}

Page({
  data: {
    tabs: TABS,
    activeTab: 0,
    isJoined: false,
    role: "leader",
    group: displayGroup({}),
    members: MEMBERS,
    applications: APPLICATIONS,
    logs: LOGS,
    visibleActions: [],
    pickerVisible: false,
    pickerTitle: "",
    pickerAction: "",
    pickerKeyword: "",
    pickerMembers: MEMBERS,
    selectedMemberIds: [],
    editVisible: false,
    newName: "",
    newLogo: "",
    pages: [1, 2, 3, 4, 5, 6],
    currentPage: 1
  },

  async onLoad(query) {
    const isJoined = query.mode === "manage" || query.joined === "1";
    const role = query.role || "leader";
    try {
      const group = await dataService.getGroup(query.id || "grp001");
      this.setData({
        group: displayGroup(group),
        isJoined,
        role,
        newName: group.name || "这是一个小组名称"
      }, () => {
        this.refreshActions();
        this.loadGroupRuntime();
      });
    } catch (error) {
      this.setData({ isJoined, role }, () => {
        this.refreshActions();
        this.loadGroupRuntime();
      });
    }
  },

  async loadGroupRuntime() {
    const id = this.data.group.id;
    if (!id) return;
    try {
      const members = (await dataService.listGroupMembers(id)).map(displayMember);
      if (members.length) {
        this.setData({
          members,
          pickerMembers: members,
          "group.members": members.length
        });
      }
    } catch (error) {
      // 非小组成员或后端未返回时保留页面已有展示数据。
    }
    try {
      const applications = (await dataService.listGroupJoinApplications(id)).map(displayApplication);
      this.setData({ applications });
    } catch (error) {
      if (this.data.isJoined) {
        this.setData({ applications: [] });
      }
    }
  },

  refreshActions() {
    this.setData({
      visibleActions: ACTIONS.filter((item) => item.roles.includes(this.data.role))
    });
  },

  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) {
      wx.navigateBack();
      return;
    }
    wx.navigateTo({ url: "/pages/organization/groups" });
  },

  copyNo() {
    wx.setClipboardData({
      data: String(this.data.group.no),
      success: () => wx.showToast({ title: "已复制编号", icon: "none" })
    });
  },

  async applyJoin() {
    try {
      await dataService.joinGroup(this.data.group.id);
      wx.showToast({ title: "已提交加入申请", icon: "none" });
    } catch (error) {
      wx.showToast({ title: error.message || "申请失败", icon: "none" });
    }
  },

  reportGroup() {
    wx.showToast({ title: "举报入口已预留", icon: "none" });
  },

  shareGroup() {
    wx.showToast({ title: "分享入口已预留", icon: "none" });
  },

  switchTab(event) {
    this.setData({ activeTab: Number(event.currentTarget.dataset.index) });
  },

  openEdit() {
    this.setData({
      editVisible: true,
      newName: this.data.group.name,
      newLogo: ""
    });
  },

  closeEdit() {
    this.setData({ editVisible: false });
  },

  updateNewName(event) {
    this.setData({ newName: event.detail.value });
  },

  chooseGroupImage() {
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
        this.setData({ newLogo: file ? file.tempFilePath : "" });
      }
    });
  },

  submitEdit() {
    const name = (this.data.newName || "").trim();
    if (!name) {
      wx.showToast({ title: "请输入新组名", icon: "none" });
      return;
    }
    this.setData({
      "group.name": name,
      "group.logo": this.data.newLogo || this.data.group.logo,
      editVisible: false
    });
    wx.showToast({ title: "资料已修改，扣除50积分", icon: "none" });
  },

  handleAction(event) {
    const action = this.data.visibleActions.find((item) => item.id === event.currentTarget.dataset.id);
    if (!action) return;
    if (action.picker) {
      this.setData({
        pickerVisible: true,
        pickerTitle: action.label,
        pickerAction: action.id,
        pickerKeyword: "",
        pickerMembers: this.data.members.map((member) => Object.assign({}, member, { selected: false })),
        selectedMemberIds: []
      });
      return;
    }
    if (action.id === "quit") {
      wx.showModal({
        title: action.label,
        content: "确认退出小组吗？",
        confirmText: "确认",
        success: async (result) => {
          if (!result.confirm) return;
          try {
            await dataService.leaveGroup(this.data.group.id);
            wx.showToast({ title: "已退出", icon: "none" });
            wx.navigateTo({ url: "/pages/organization/groups" });
          } catch (error) {
            wx.showToast({ title: error.message || "退出失败", icon: "none" });
          }
        }
      });
      return;
    }
    wx.showModal({
      title: action.label,
      content: "该操作需后台管理端处理。",
      confirmText: "确认",
      success: (result) => {
        if (result.confirm) {
          wx.showToast({ title: "请到后台管理端处理", icon: "none" });
        }
      }
    });
  },

  closePicker() {
    this.setData({ pickerVisible: false });
  },

  onPickerSearch(event) {
    const keyword = (event.detail.value || "").trim();
    const pickerMembers = this.data.members
      .filter((member) => member.name.includes(keyword))
      .map((member) => Object.assign({}, member, {
        selected: this.data.selectedMemberIds.includes(member.id)
      }));
    this.setData({ pickerKeyword: keyword, pickerMembers });
  },

  toggleMember(event) {
    const id = event.currentTarget.dataset.id;
    const selected = this.data.selectedMemberIds.slice();
    const index = selected.indexOf(id);
    if (index >= 0) {
      selected.splice(index, 1);
    } else {
      selected.push(id);
    }
    const pickerMembers = this.data.pickerMembers.map((member) => {
      if (member.id !== id) return member;
      return Object.assign({}, member, { selected: !member.selected });
    });
    this.setData({ selectedMemberIds: selected, pickerMembers });
  },

  async confirmPicker() {
    if (!this.data.selectedMemberIds.length) {
      wx.showToast({ title: "请选择成员", icon: "none" });
      return;
    }
    const id = this.data.group.id;
    const action = this.data.pickerAction;
    try {
      await Promise.all(this.data.selectedMemberIds.map((memberId) => {
        if (action === "setAdmin") return dataService.setGroupMemberAdmin(id, memberId, true);
        if (action === "removeAdmin") return dataService.setGroupMemberAdmin(id, memberId, false);
        if (action === "removeMember") return dataService.deleteGroupMember(id, memberId);
        return Promise.reject(new Error("该操作需后台管理端处理"));
      }));
      wx.showToast({ title: `${this.data.pickerTitle}已提交`, icon: "none" });
      this.setData({ pickerVisible: false });
      this.loadGroupRuntime();
    } catch (error) {
      wx.showToast({ title: error.message || "操作失败", icon: "none" });
    }
  },

  toggleApplication(event) {
    const id = event.currentTarget.dataset.id;
    const applications = this.data.applications.map((item) => {
      if (item.id !== id) return item;
      return Object.assign({}, item, { checked: !item.checked });
    });
    this.setData({ applications });
  },

  approveApplications() {
    this.handleApplications("通过");
  },

  rejectApplications() {
    this.handleApplications("拒绝");
  },

  async handleApplications(action) {
    const checked = this.data.applications.filter((item) => item.checked);
    if (!checked.length) {
      wx.showToast({ title: "请选择申请人", icon: "none" });
      return;
    }
    try {
      await Promise.all(checked.map((item) => {
        return action === "通过"
          ? dataService.approveGroupMember(this.data.group.id, item.memberId || item.id)
          : dataService.rejectGroupMember(this.data.group.id, item.memberId || item.id);
      }));
      wx.showToast({ title: `已${action}`, icon: "none" });
      this.loadGroupRuntime();
    } catch (error) {
      wx.showToast({ title: error.message || "审批失败", icon: "none" });
    }
  },

  goActivity(event) {
    wx.navigateTo({ url: `/pages/activity/detail?id=${event.currentTarget.dataset.id}` });
  }
});
