const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");
const { GUARANTEE_ORDER, guaranteeIcon } = require("../../utils/service-guarantees");

Page({
  data: {
    submitting: false,
    uploadingCover: false,
    permissionUnknown: false,
    // 表单默认全空，由负责人逐项填写；只保留无可见输入项、payload 需要的隐藏配置默认值（倍率/半径/审核/范围/门槛）
    form: {
      title: "",
      coverImageUrl: "",
      location: "",
      enrollDeadline: "",
      enrollOpenManager: "",
      enrollOpenLeader: "",
      enrollOpenVolunteer: "",
      pointsBase: "",
      managerMultiplier: "1.2",
      leaderMultiplier: "1.4",
      // 活动场次（可多个）：每个 {projectName, startTime, endTime, needCount}；活动整体起止由各场次最早~最晚派生
      slots: [{ projectName: "", startTime: "", endTime: "", needCount: "" }],
      contactName: "",
      contactPhone: "",
      publisherDeptName: "",
      content: "",
      requirement: "",
      enrollNotice: "",
      requireMinJoinCount: "0",
      requireMinJoinMinutes: "0",
      checkInRadiusM: "500",
      needAudit: "0",
      enrollScope: "0",
      lat: "",
      lng: ""
    },
    // 服务保障 12 选 N（与详情页同源常量）；提交时取 selected 的 key
    guarantees: GUARANTEE_ORDER.map((g) => ({
      key: g.key, label: g.label, asset: g.asset, selected: false, iconUrl: guaranteeIcon(g.asset, false)
    }))
  },

  async onLoad() {
    if (!auth.requireLogin()) return;
    try {
      const permissions = await dataService.loadMyPermissions();
      auth.setPermissions(permissions);
    } catch (error) {
      if (!auth.getPermissions().length) auth.setPermissions([]);
      this.setData({ permissionUnknown: true });
    }
    if (!this.data.permissionUnknown && !auth.hasPermission("activity:publish")) {
      wx.showToast({ title: "暂无权限", icon: "none" });
      wx.navigateBack();
    }
  },

  goBack() {
    wx.navigateBack();
  },

  updateField(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    const patch = { [`form.${field}`]: event.detail.value };
    // 手动改地点会让之前「定位」选的经纬度失效——清掉旧坐标，避免「文字是新地址、GPS 签到点还是旧坐标」
    if (field === "location") {
      patch["form.lat"] = "";
      patch["form.lng"] = "";
    }
    this.setData(patch);
  },

  // 多场次：改某个场次的某字段 / 增 / 删
  updateSlot(event) {
    const { index, field } = event.currentTarget.dataset;
    if (index == null || !field) return;
    const i = Number(index);
    const slots = this.data.form.slots.map((s, idx) => (idx === i ? Object.assign({}, s, { [field]: event.detail.value }) : s));
    this.setData({ "form.slots": slots });
  },

  addSlot() {
    this.setData({ "form.slots": this.data.form.slots.concat([{ projectName: "", startTime: "", endTime: "", needCount: "" }]) });
  },

  removeSlot(event) {
    if (this.data.form.slots.length <= 1) return;
    const i = Number(event.currentTarget.dataset.index);
    this.setData({ "form.slots": this.data.form.slots.filter((s, idx) => idx !== i) });
  },

  // 选图 → 按 16:9 居中裁剪 → 传到 /v/files/upload → 回填 form.coverImageUrl（存服务器 URL）。
  // 裁成 16:9 与后台封面 preset:'cover' 契约一致，避免竖图/超宽图进封面后在列表/详情被 aspectFill 乱切。
  chooseCover() {
    if (this.data.uploadingCover) return;
    const chooseMedia = wx.chooseMedia || wx.chooseImage;
    if (!chooseMedia) {
      wx.showToast({ title: "当前版本不支持选图", icon: "none" });
      return;
    }
    chooseMedia({
      count: 1,
      mediaType: ["image"],
      sourceType: ["album", "camera"],
      success: async (res) => {
        const file = res.tempFiles ? res.tempFiles[0] : res.tempFilePaths && { tempFilePath: res.tempFilePaths[0] };
        const tempFilePath = file && file.tempFilePath;
        if (!tempFilePath) return;
        this.setData({ uploadingCover: true });
        wx.showLoading({ title: "上传中", mask: true });
        try {
          const cropped = await this.cropCoverTo16by9(tempFilePath);
          const url = await dataService.uploadActivityImage(cropped);
          this.setData({ "form.coverImageUrl": url });
          wx.hideLoading();
          wx.showToast({ title: "上传成功", icon: "success" });
        } catch (error) {
          wx.hideLoading();
          wx.showToast({ title: error.message || "上传失败", icon: "none" });
        } finally {
          this.setData({ uploadingCover: false });
        }
      }
    });
  },

  // 用隐藏的 2d canvas 把图按 16:9 居中裁剪并缩到 ≤1280 宽，返回新临时文件路径。
  // 任一步骤失败都兜底返回原图（裁剪是体验优化，不该挡住上传主流程）。
  cropCoverTo16by9(tempFilePath) {
    const RATIO = 16 / 9;
    return new Promise((resolve) => {
      wx.getImageInfo({
        src: tempFilePath,
        success: (info) => {
          const sw = info.width;
          const sh = info.height;
          // 源图上裁出最大的 16:9 居中矩形
          let cropW = sw;
          let cropH = Math.round(sw / RATIO);
          if (cropH > sh) {
            cropH = sh;
            cropW = Math.round(sh * RATIO);
          }
          const sx = Math.round((sw - cropW) / 2);
          const sy = Math.round((sh - cropH) / 2);
          const outW = Math.min(cropW, 1280);
          const outH = Math.round(outW / RATIO);

          wx.createSelectorQuery()
            .select("#coverCanvas")
            .fields({ node: true, size: true })
            .exec((res) => {
              const node = res && res[0] && res[0].node;
              if (!node) {
                resolve(tempFilePath);
                return;
              }
              node.width = outW;
              node.height = outH;
              const ctx = node.getContext("2d");
              const img = node.createImage();
              img.onload = () => {
                ctx.drawImage(img, sx, sy, cropW, cropH, 0, 0, outW, outH);
                wx.canvasToTempFilePath({
                  canvas: node,
                  destWidth: outW,
                  destHeight: outH,
                  fileType: "jpg",
                  quality: 0.9,
                  success: (r) => resolve(r.tempFilePath),
                  fail: () => resolve(tempFilePath)
                });
              };
              img.onerror = () => resolve(tempFilePath);
              img.src = tempFilePath;
            });
        },
        fail: () => resolve(tempFilePath)
      });
    });
  },

  removeCover() {
    this.setData({ "form.coverImageUrl": "" });
  },

  // 服务保障不定项选择：点选切换红/灰图标
  toggleGuarantee(event) {
    const key = event.currentTarget.dataset.key;
    if (!key) return;
    const guarantees = this.data.guarantees.map((g) =>
      g.key === key ? { ...g, selected: !g.selected, iconUrl: guaranteeIcon(g.asset, !g.selected) } : g
    );
    this.setData({ guarantees });
  },

  // 活动地点：点「定位」调起微信地图选点（与注册/资料页同款），回填地址文本 + 经纬度（GPS 签到用）；仍可手动改地址
  chooseActivityLocation() {
    if (!wx.chooseLocation) {
      wx.showToast({ title: "当前版本不支持地图选点，可直接手写地点", icon: "none" });
      return;
    }
    wx.chooseLocation({
      success: (res) => {
        const name = res.name || "";
        const address = res.address || "";
        const locationText = address && name && address.includes(name)
          ? address
          : [address, name].filter(Boolean).join(" ");
        const patch = {};
        if (locationText) patch["form.location"] = locationText;
        if (typeof res.latitude === "number") patch["form.lat"] = String(res.latitude);
        if (typeof res.longitude === "number") patch["form.lng"] = String(res.longitude);
        this.setData(patch);
      },
      fail: (err) => {
        if (err && /cancel/i.test(err.errMsg || "")) return;
        wx.showToast({ title: "地图选点失败，可直接手写地点", icon: "none" });
      }
    });
  },

  async submitPublish() {
    const f = this.data.form;
    // 必填项（与表单粉色 * 一一对应，须真正拦截，避免 * 形同虚设）：
    // 活动照片/封面、活动名称、活动地点、活动要求、积分 + 每个场次的项目名称/开始/结束/需求人数。
    // 截止报名/报名审核/参加次数等有默认值，按需求「不填默认」处理，不强制。
    const required = [
      [f.title, "活动名称"],
      [f.coverImageUrl, "活动封面图"],
      [f.location, "活动地点"],
      [f.requirement, "活动要求"],
      [f.pointsBase, "积分"]
    ];
    const isBlank = (v) => v === undefined || v === null || String(v).trim() === "";
    const missing = required.find(([v]) => isBlank(v));
    if (missing) {
      wx.showToast({ title: `请填写${missing[1]}`, icon: "none" });
      return;
    }
    // 逐场次校验：每个场次的项目名称/开始/结束/需求人数必填，需求人数=正整数。
    // 数值项挡非法输入（如 "." / "10.5" / "abc"），否则会被 data-service 的 numberOrDefault 静默回落成默认值，
    // 让“非空但非法”绕过必填。
    const slots = Array.isArray(f.slots) ? f.slots : [];
    if (!slots.length) {
      wx.showToast({ title: "请至少添加一个场次", icon: "none" });
      return;
    }
    for (let i = 0; i < slots.length; i += 1) {
      const s = slots[i];
      const prefix = slots.length > 1 ? `场次${i + 1}` : "";
      const slotRequired = [
        [s.projectName, "项目名称"],
        [s.startTime, "开始时间"],
        [s.endTime, "结束时间"],
        [s.needCount, "需求人数"]
      ];
      const miss = slotRequired.find(([v]) => isBlank(v));
      if (miss) {
        wx.showToast({ title: `请填写${prefix}${miss[1]}`, icon: "none" });
        return;
      }
      const need = Number(s.needCount);
      if (!Number.isFinite(need) || !Number.isInteger(need) || need < 1) {
        wx.showToast({ title: `${prefix}需求人数需为大于0的整数`, icon: "none" });
        return;
      }
    }
    // 积分=非负整数
    const points = Number(f.pointsBase);
    if (!Number.isFinite(points) || !Number.isInteger(points) || points < 0) {
      wx.showToast({ title: "积分需为不小于0的整数", icon: "none" });
      return;
    }
    if (!this.data.permissionUnknown && !auth.hasPermission("activity:publish")) {
      wx.showToast({ title: "暂无权限", icon: "none" });
      return;
    }

    this.setData({ submitting: true });
    try {
      const serviceGuarantees = this.data.guarantees.filter((g) => g.selected).map((g) => g.key);
      await dataService.publishActivity(Object.assign({}, this.data.form, { serviceGuarantees }));
      wx.showToast({ title: "发布成功", icon: "success" });
      setTimeout(() => wx.redirectTo({ url: "/pages/activity-leader/index" }), 700);
    } catch (error) {
      // request.js 现在抛带 statusCode/message 的错误：403 提示无权限，其余直接显示后端校验信息
      wx.showToast({ title: error.statusCode === 403 ? "暂无权限" : (error.message || "发布失败"), icon: "none" });
      this.setData({ submitting: false });
    }
  }
});
