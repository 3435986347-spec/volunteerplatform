/* ============================================================
   预览身份（design / preview 用）
   未真实登录时，Tweaks「身份视角」用这 4 套 mock 身份预览不同账号看到的菜单/按钮差异。
   真实登录后 GET /a/auth/me 返回的身份会覆盖它（app.js: identity = me || PREVIEW_IDENTITIES[...]）。
   生产运行不依赖本文件的数据正确性——仅为无后端时的设计预览辅助。
   ============================================================ */
var PREVIEW_IDENTITIES = {
  super: {
    key: 'super', adminId: 1, name: '陈国栋', account: 'admin',
    dept: '协会理事会', phone: '13800138888', phoneTail: '8888',
    isSuperAdmin: true, permissionCodes: ['*'],
    roleLabel: '超级管理员',
  },
  org: {
    key: 'org', adminId: 7, name: '林海燕', account: 'zuzhi01',
    dept: '组织部', phone: '13902460012', phoneTail: '0012',
    isSuperAdmin: false,
    permissionCodes: ['user:list', 'user:status', 'user:export', 'activity:enroll-view', 'activity:enroll-audit', 'activity:publish-audit', 'org:group-audit', 'org:squad-manage', 'org:squad-audit'],
    roleLabel: '组织部 · 子账号',
  },
  secretary: {
    key: 'secretary', adminId: 9, name: '吴敏', account: 'mishu01',
    dept: '秘书部', phone: '13750998341', phoneTail: '8341',
    isSuperAdmin: false,
    permissionCodes: ['activity:service-confirm', 'activity:points-grant', 'activity:attendance-edit'],
    roleLabel: '秘书部 · 子账号',
  },
  publicity: {
    key: 'publicity', adminId: 12, name: '黄梓萱', account: 'xuanchuan01',
    dept: '宣传部', phone: '13602331567', phoneTail: '1567',
    isSuperAdmin: false,
    permissionCodes: ['pub:banner', 'pub:announcement', 'pub:file'],
    roleLabel: '宣传部 · 子账号',
  },
};

window.PREVIEW_IDENTITIES = PREVIEW_IDENTITIES;
