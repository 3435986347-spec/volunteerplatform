const auth = require("../../utils/auth");
const dataService = require("../../utils/data-service");

Page({
  data: {
    submitting: false,
    uploadingCover: false,
    permissionUnknown: false,
    form: {
      title: "公益书屋项目书籍整理活动",
      coverImageUrl: "",
      location: "御景雅苑（东方三路）",
      startTime: "2026-06-10 09:00",
      endTime: "2026-06-10 12:00",
      enrollDeadline: "2026-06-09 18:00",
      enrollOpenManager: "2026-06-09 08:00",
      enrollOpenLeader: "2026-06-09 08:00",
      enrollOpenVolunteer: "2026-06-09 08:00",
      pointsBase: "10",
      managerMultiplier: "1.2",
      leaderMultiplier: "1.1",
      slotProjectName: "志愿者",
      slotNeedCount: "20",
      contactName: "邝大程",
      contactPhone: "15766508094",
      publisherDeptName: "组织部",
      content: "请按岗位时间到达现场，服从负责人安排。",
      requirement: "完成实名注册即可报名。",
      enrollNotice: "报名成功后请准时参加。",
      requireMinJoinCount: "0",
      requireMinJoinMinutes: "0",
      checkInRadiusM: "500",
      needAudit: "0",
      enrollScope: "0",
      lat: "",
      lng: ""
    }
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
    this.setData({
      [`form.${field}`]: event.detail.value
    });
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

  async submitPublish() {
    const { title, location, startTime, endTime, slotNeedCount } = this.data.form;
    if (!title || !location || !startTime || !endTime || !slotNeedCount) {
      wx.showToast({ title: "请填写完整活动信息", icon: "none" });
      return;
    }
    if (!this.data.permissionUnknown && !auth.hasPermission("activity:publish")) {
      wx.showToast({ title: "暂无权限", icon: "none" });
      return;
    }

    this.setData({ submitting: true });
    try {
      await dataService.publishActivity(this.data.form);
      wx.showToast({ title: "发布成功", icon: "success" });
      setTimeout(() => wx.redirectTo({ url: "/pages/activity-leader/index" }), 700);
    } catch (error) {
      // request.js 现在抛带 statusCode/message 的错误：403 提示无权限，其余直接显示后端校验信息
      wx.showToast({ title: error.statusCode === 403 ? "暂无权限" : (error.message || "发布失败"), icon: "none" });
      this.setData({ submitting: false });
    }
  }
});
