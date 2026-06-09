/* ============================================================
   恒德志愿者平台 · 后台 — Mock 数据 + 权限码模型
   纯前端 mock；权限驱动 UI 的全部依据。
   ⚠ 0 号前置：后端暂无 GET /a/auth/me，此处 mock 当前管理员资料 + 权限码。
   ============================================================ */
(function (w) {
  /* ---------- 权限点目录（GET /a/organization/permissions 的形态） ---------- */
  // module 分组：activity / organization / publicity （user/data 暂未实现接口）
  var PERM_CATALOG = [
    { module: 'user', label: '用户管理', perms: [
      { id: 1, code: 'user:list', name: '志愿者查看', desc: '志愿者列表/详情（含明文手机号）' },
      { id: 2, code: 'user:status', name: '暂停/恢复账号', desc: '停用 / 启用志愿者' },
      { id: 3, code: 'user:delete', name: '删除志愿者', desc: '逻辑删除志愿者' },
      { id: 4, code: 'user:export', name: '导出志愿者', desc: '导出志愿者名单 Excel' },
      { id: 5, code: 'user:pwd-reset', name: '重置志愿者密码', desc: '契约兼容（微信登录无密码）' },
      // 注：修改实名敏感资料 user:edit 写死仅超管，不入可分配目录
    ]},
    { module: 'activity', label: '活动管理', perms: [
      { id: 11, code: 'activity:menu', name: '活动菜单/列表', desc: '查看活动列表与详情' },
      { id: 12, code: 'activity:publish', name: '发布活动', desc: '发布 / 周期发布 / 复制 / 历史补录载体' },
      { id: 13, code: 'activity:edit', name: '修改活动', desc: '编辑已发布活动' },
      { id: 14, code: 'activity:delete', name: '删除活动', desc: '删除活动（不可逆）' },
      { id: 15, code: 'activity:enroll-view', name: '报名查看', desc: '查看某活动报名名单' },
      { id: 16, code: 'activity:enroll-audit', name: '报名审核', desc: '通过 / 拒绝报名' },
      { id: 17, code: 'activity:enroll-add', name: '手动新增报名', desc: '越权补录报名' },
      { id: 18, code: 'activity:enroll-export', name: '导出名单', desc: '导出报名 Excel' },
      { id: 19, code: 'activity:enroll-delete', name: '删除报名', desc: '删除报名记录' },
      { id: 20, code: 'activity:leader-assign', name: '指派负责人', desc: '指派 / 取消活动负责人' },
      { id: 21, code: 'activity:manage', name: '现场管理', desc: '开始/结束/签退/到位/违规/总结' },
      { id: 22, code: 'activity:service-confirm', name: '确认服务时长', desc: '秘书部确认已签退时长' },
      { id: 23, code: 'activity:points-grant', name: '发放积分', desc: '正常/减半/不发' },
      { id: 24, code: 'activity:attendance-edit', name: '考勤变更申请', desc: '申请改签到/签退/积分' },
      { id: 25, code: 'activity:attendance-audit', name: '考勤变更审核', desc: '审核考勤变更申请' },
      { id: 26, code: 'activity:backfill', name: '补录申请', desc: '历史活动补登考勤' },
      { id: 27, code: 'activity:backfill-audit', name: '补录审核', desc: '审核补录申请' },
      { id: 28, code: 'activity:publish-audit', name: '活动发布审核', desc: '审核志愿者（管理团队）发布的活动' },
    ]},
    { module: 'organization', label: '组织管理', perms: [
      { id: 31, code: 'org:group-manage', name: '小组管理', desc: '列表/解散/转移组长/批量导入' },
      { id: 32, code: 'org:group-audit', name: '建组审批', desc: '批准 / 拒绝建组申请' },
      { id: 33, code: 'org:squad-manage', name: '分队管理', desc: '分队增删改查' },
      { id: 34, code: 'org:squad-audit', name: '分队加入审核', desc: '批准 / 拒绝加入分队' },
      { id: 35, code: 'org:manager-flag', name: '管理团队标记', desc: '标记/取消管理团队', superGrant: true },
      { id: 36, code: 'org:sub-account', name: '子账号与权限', desc: '子账号增删改查 + 权限分配', superGrant: true },
    ]},
    { module: 'publicity', label: '信息公示', perms: [
      { id: 41, code: 'pub:banner', name: '轮播图', desc: '首页轮播图管理' },
      { id: 42, code: 'pub:announcement', name: '公告', desc: '公告管理（草稿/发布）' },
      { id: 43, code: 'pub:file', name: '文件下载', desc: '文件上传/删除/开关下载' },
    ]},
  ];
  var ALL_CODES = [];
  PERM_CATALOG.forEach(function (m) { m.perms.forEach(function (p) { ALL_CODES.push(p.code); }); });

  /* 可授权给志愿者的权限点（活动域子集，除 activity:menu）——对照 url 文档 volunteer-grantable */
  var VOLUNTEER_GRANTABLE = ['activity:publish', 'activity:leader-assign', 'activity:manage', 'activity:enroll-view', 'activity:enroll-audit'];

  /* ---------- 身份档案（mock GET /a/auth/me） ---------- */
  var IDENTITIES = {
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

  function hasPerm(identity, code) {
    if (!identity) return false;
    if (identity.isSuperAdmin || identity.permissionCodes.indexOf('*') >= 0) return true;
    if (!code) return true; // 登录即可
    if (Array.isArray(code)) return code.some(function (c) { return identity.permissionCodes.indexOf(c) >= 0; });
    return identity.permissionCodes.indexOf(code) >= 0;
  }

  /* ============================================================ MOCK 业务数据 */

  /* 活动 run_status: 0未开始 / 1进行中 / 2已结束；status: 0草稿 1报名中 2报名截止 3已结束 4历史 */
  var ACTIVITIES = [
    { id: 1001, title: '城西社区敬老助残志愿服务', cat: '助老', date: '2026-06-08', time: '08:30–11:30', place: '城西街道阳光养老院', lng: 110.0972, lat: 20.9213, radius: 200, enrolled: 32, quota: 40, status: 1, run: 0, contact: '林海燕', contactPhone: '139****0012', leaderCount: 2, summary: false },
    { id: 1002, title: '雷州西湖公园河道清洁行动', cat: '环保', date: '2026-06-01', time: '15:00–17:30', place: '雷州西湖公园北门', lng: 110.0801, lat: 20.9145, radius: 150, enrolled: 28, quota: 30, status: 1, run: 1, contact: '陈国栋', contactPhone: '138****8888', leaderCount: 3, summary: false },
    { id: 1003, title: '留守儿童暑期阅读陪伴（第3期）', cat: '助学', date: '2026-05-28', time: '09:00–11:00', place: '附城镇中心小学', lng: 110.1123, lat: 20.8876, radius: 120, enrolled: 24, quota: 24, status: 3, run: 2, contact: '吴敏', contactPhone: '137****8341', leaderCount: 2, summary: true },
    { id: 1004, title: '夏季无偿献血宣传引导', cat: '公益宣传', date: '2026-06-15', time: '09:00–16:00', place: '雷州市人民广场', lng: 110.0934, lat: 20.9267, radius: 300, enrolled: 12, quota: 20, status: 1, run: 0, contact: '黄梓萱', contactPhone: '136****1567', leaderCount: 1, summary: false },
    { id: 1005, title: '台风灾后社区清理互助', cat: '应急', date: '2026-05-20', time: '07:30–12:00', place: '东里镇受灾片区', lng: 110.2451, lat: 20.7689, radius: 500, enrolled: 45, quota: 50, status: 3, run: 2, contact: '陈国栋', contactPhone: '138****8888', leaderCount: 4, summary: false },
    { id: 1006, title: '文明交通劝导周末岗', cat: '文明实践', date: '2026-06-13', time: '17:00–19:00', place: '雷州大道与西湖路口', lng: 110.0889, lat: 20.9201, radius: 100, enrolled: 8, quota: 16, status: 1, run: 0, contact: '林海燕', contactPhone: '139****0012', leaderCount: 0, summary: false },
    { id: 1007, title: '关爱困境家庭物资派发', cat: '济困', date: '2026-04-12', time: '09:00–12:00', place: '雷城街道办事处', lng: 110.0956, lat: 20.9189, radius: 180, enrolled: 22, quota: 22, status: 4, run: 2, contact: '吴敏', contactPhone: '137****8341', leaderCount: 2, summary: true },
  ];

  /* 待审核的"志愿者发布活动"（小程序管理团队志愿者 POST /v/activity/activities → status=4 待审核发布）
     审核 status：4 待审核 / 1 通过即上线 / 5 已驳回（带 reason/reviewer/reviewTime） */
  var PENDING_ACTIVITIES = [
    { id: 8801, title: '乌石镇海滩净滩公益行动', cat: '环保', publisher: '周浩然', publisherPhone: '139****3344', publisherTeam: '管理团队', source: '小程序发布', date: '2026-06-22', time: '08:00–11:00', place: '雷州乌石镇金沙湾海滩', lng: 109.7821, lat: 20.6534, radius: 500, quota: 30, submitTime: '06-01 09:20', status: 4, note: '需协会提供垃圾袋与手套，已联系村委' },
    { id: 8802, title: '社区反诈骗宣传周末岗', cat: '公益宣传', publisher: '李明轩', publisherPhone: '139****1122', publisherTeam: '管理团队', source: '小程序发布', date: '2026-06-28', time: '15:00–17:00', place: '雷城街道社区广场', lng: 110.0945, lat: 20.9203, radius: 300, quota: 20, submitTime: '06-01 14:05', status: 4, note: '联合派出所开展，需准备宣传单' },
    { id: 8803, title: '附城敬老院重阳节慰问', cat: '助老', publisher: '王雅琪', publisherPhone: '137****1022', publisherTeam: '管理团队', source: '小程序发布', date: '2026-06-18', time: '09:00–11:30', place: '附城镇敬老院', lng: 110.1118, lat: 20.8869, radius: 200, quota: 16, submitTime: '05-31 20:40', status: 4, note: '准备水果与文艺节目' },
    { id: 8804, title: '夜市食品安全志愿巡查', cat: '文明实践', publisher: '黄思远', publisherPhone: '133****0099', publisherTeam: '管理团队', source: '小程序发布', date: '2026-06-10', time: '19:00–21:00', place: '雷州西湖夜市', lng: 110.0795, lat: 20.9138, radius: 400, quota: 12, submitTime: '05-30 22:14', status: 5, reviewer: '林海燕（组织部）', reviewTime: '05-31 09:10', rejectReason: '与市监局活动时间冲突，建议改期后重新提交', note: '配合市监局开展' },
  ];

  /* 报名（按活动）enrollStatus: 0待审 1通过 2拒绝；source: 自报名 / 代报名（代报人） */
  var ENROLLMENTS = {
    1001: [
      { id: 5001, name: '李文博', phone: '13902460111', school: '岭南师范学院', enrollStatus: 1, source: '自报名', proxy: null, time: '05-29 19:12' },
      { id: 5002, name: '王雅琪', phone: '13750991022', school: '岭南师范学院', enrollStatus: 1, source: '自报名', proxy: null, time: '05-29 20:01' },
      { id: 5003, name: '张俊辉', phone: '13602331330', school: '广东医科大学', enrollStatus: 0, source: '自报名', proxy: null, time: '05-30 08:45' },
      { id: 5004, name: '陈嘉怡', phone: '13822445566', school: '雷州一中', enrollStatus: 0, source: '代报名', proxy: '林海燕', time: '05-30 09:03' },
      { id: 5005, name: '刘子墨', phone: '13509981234', school: '岭南师范学院', enrollStatus: 2, source: '自报名', proxy: null, time: '05-29 21:30', reason: '名额已满，建议改报第二场' },
      { id: 5006, name: '黄思远', phone: '13312340099', school: '广东海洋大学', enrollStatus: 0, source: '自报名', proxy: null, time: '05-30 10:21' },
    ],
    1002: [
      { id: 5101, name: '周浩然', phone: '13911223344', school: '岭南师范学院', enrollStatus: 1, source: '自报名', proxy: null, time: '05-28 18:00' },
      { id: 5102, name: '吴佳颖', phone: '13755667788', school: '雷州二中', enrollStatus: 1, source: '自报名', proxy: null, time: '05-28 18:22' },
      { id: 5103, name: '郑伟杰', phone: '13688990011', school: '广东医科大学', enrollStatus: 0, source: '自报名', proxy: null, time: '05-29 12:10' },
    ],
  };

  /* 现场签到名单 attendStatus:1正常 2请假 3迟到 4缺席；checkIn/checkOut 时间或 null */
  var ATTENDANCES = {
    1002: [
      { id: 7001, vid: 9001, name: '周浩然', phone: '13911223344', school: '岭南师范学院', checkIn: '14:58', checkOut: null, attendStatus: 1, violations: 0 },
      { id: 7002, vid: 9002, name: '吴佳颖', phone: '13755667788', school: '雷州二中', checkIn: '15:06', checkOut: null, attendStatus: 3, violations: 0 },
      { id: 7003, vid: 9003, name: '郑伟杰', phone: '13688990011', school: '广东医科大学', checkIn: null, checkOut: null, attendStatus: 4, violations: 1 },
      { id: 7004, vid: 9004, name: '李明轩', phone: '13900011122', school: '岭南师范学院', checkIn: '14:50', checkOut: null, attendStatus: 1, violations: 0 },
      { id: 7005, vid: 9005, name: '何静怡', phone: '13755009988', school: '雷州一中', checkIn: null, checkOut: null, attendStatus: 2, violations: 0 },
    ],
  };
  var LEADERS = {
    1002: [
      { id: 8001, name: '陈国栋', leaderType: 2, role: '总负责', phone: '138****8888' },
      { id: 8002, name: '周浩然', leaderType: 1, role: '签到组', phone: '139****3344' },
      { id: 8003, name: '李明轩', leaderType: 1, role: '物资组', phone: '139****1122' },
    ],
  };
  var VIOLATIONS = [
    { id: 1, name: '郑伟杰', activity: '雷州西湖公园河道清洁行动', type: '缺席', desc: '报名后未到场，未请假（系统自动记违规）', by: '系统', time: '06-01 17:30' },
    { id: 2, name: '刘子墨', activity: '留守儿童暑期阅读陪伴（第3期）', type: '提前离场', desc: '签到后约 40 分钟离场，未完成服务', by: '吴敏', time: '05-28 10:05' },
  ];

  /* 服务记录与积分 secretaryStatus: 0未确认 1已确认；pointsStatus:0未发 1已发 */
  var SERVICE_RECORDS = [
    { id: 6001, name: '李文博', activity: '留守儿童暑期阅读陪伴（第3期）', date: '2026-05-28', hours: 2.0, secretaryStatus: 1, points: 4, pointsStatus: 1 },
    { id: 6002, name: '王雅琪', activity: '留守儿童暑期阅读陪伴（第3期）', date: '2026-05-28', hours: 2.0, secretaryStatus: 1, points: 4, pointsStatus: 1 },
    { id: 6003, name: '张俊辉', activity: '台风灾后社区清理互助', date: '2026-05-20', hours: 4.5, secretaryStatus: 0, points: 0, pointsStatus: 0 },
    { id: 6004, name: '陈嘉怡', activity: '台风灾后社区清理互助', date: '2026-05-20', hours: 4.5, secretaryStatus: 0, points: 0, pointsStatus: 0 },
    { id: 6005, name: '黄思远', activity: '台风灾后社区清理互助', date: '2026-05-20', hours: 2.0, secretaryStatus: 1, points: 0, pointsStatus: 0 },
    { id: 6006, name: '周浩然', activity: '关爱困境家庭物资派发', date: '2026-04-12', hours: 3.0, secretaryStatus: 1, points: 6, pointsStatus: 1 },
  ];

  /* 考勤变更审核 status:0待审 1通过 2拒绝 */
  var ATTENDANCE_CHANGES = [
    { id: 4101, name: '郑伟杰', activity: '雷州西湖公园河道清洁行动', field: '到位状态', oldVal: '缺席', newVal: '请假', oldPoints: 0, newPoints: 0, applicant: '林海燕（组织部）', reason: '志愿者提前电话请假，签到点漏登', status: 0, time: '06-01 18:40' },
    { id: 4102, name: '何静怡', activity: '台风灾后社区清理互助', field: '签退时间', oldVal: '11:00', newVal: '12:00', oldPoints: 6, newPoints: 8, applicant: '林海燕（组织部）', reason: '实际服务至 12:00，现场负责人确认', status: 0, time: '05-21 09:12' },
    { id: 4103, name: '黄思远', activity: '台风灾后社区清理互助', field: '积分', oldVal: '0', newVal: '4', oldPoints: 0, newPoints: 4, applicant: '吴敏（秘书部）', reason: '服务时长达标，应补发积分', status: 1, time: '05-22 14:30' },
  ];

  /* 补录审核 status:0/1/2 */
  var BACKFILLS = [
    { id: 3101, vname: '吴桂兰', vphone: '13509987654', idTail: '4321', activity: '关爱困境家庭物资派发（历史）', period: '2026-04-12 09:00–12:00', planHours: 3.0, planPoints: 6, applicant: '吴敏（秘书部）', reason: '纸质签到表补录，本人当日到场', status: 0, time: '05-30 16:20' },
    { id: 3102, vname: '梁志强', vphone: '13822119900', idTail: '8876', activity: '雷城义诊便民服务（历史）', period: '2026-03-15 08:30–11:30', planHours: 3.0, planPoints: 6, applicant: '吴敏（秘书部）', reason: '系统上线前的线下活动补登', status: 0, time: '05-31 10:02' },
    { id: 3103, vname: '苏婉清', vphone: '13755338822', idTail: '1102', activity: '春节关爱孤寡老人走访（历史）', period: '2026-02-08 14:00–17:00', planHours: 3.0, planPoints: 6, applicant: '陈国栋', reason: '老志愿者历史服务补录', status: 1, time: '05-25 11:40' },
  ];

  /* 志愿小组 status:0待审 1正常 2已解散 */
  var GROUPS = [
    { id: 2001, name: '城西助老先锋队', leader: '李文博', leaderPhone: '139****0111', members: 18, status: 1, createTime: '2025-09-12', approveTime: '2025-09-13' },
    { id: 2002, name: '西湖环保骑士', leader: '周浩然', leaderPhone: '139****3344', members: 24, status: 1, createTime: '2025-10-08', approveTime: '2025-10-09' },
    { id: 2003, name: '暖阳助学小队', leader: '王雅琪', leaderPhone: '137****1022', members: 12, status: 1, createTime: '2026-01-15', approveTime: '2026-01-16' },
    { id: 2004, name: '应急救援互助组', leader: '郑伟杰', leaderPhone: '136****0011', members: 9, status: 2, createTime: '2025-06-20', approveTime: '2025-06-21' },
  ];
  var GROUP_APPLICATIONS = [
    { id: 9101, name: '雷城交通文明岗小队', applicant: '陈嘉怡', phone: '138****5566', school: '雷州一中', members: 6, reason: '长期负责雷州大道路口文明劝导，希望成立固定小组', status: 0, time: '05-30 14:20' },
    { id: 9102, name: '海洋大学环保社志愿组', applicant: '黄思远', phone: '133****0099', school: '广东海洋大学', members: 15, reason: '校社团整体加入协会，承接河道与海滩清洁', status: 0, time: '05-31 09:50' },
  ];
  var GROUP_LEADER_HISTORY = {
    2001: [
      { time: '2025-09-13', from: null, to: '李文博', by: '系统（建组通过）' },
      { time: '2026-02-20', from: '李文博', to: '刘子墨', by: '陈国栋（超管）' },
      { time: '2026-03-05', from: '刘子墨', to: '李文博', by: '陈国栋（超管）' },
    ],
  };

  /* 归属分队 */
  var SQUADS = [
    { id: 2201, name: '城区第一分队', captain: '李文博', members: 42, cap: 60, createTime: '2025-08-01', pending: 3 },
    { id: 2202, name: '城区第二分队', captain: '周浩然', members: 38, cap: 50, createTime: '2025-08-01', pending: 0 },
    { id: 2203, name: '附城镇分队', captain: '王雅琪', members: 26, cap: 40, createTime: '2025-09-10', pending: 2 },
    { id: 2204, name: '东里镇分队', captain: '郑伟杰', members: 19, cap: 40, createTime: '2025-11-22', pending: 0 },
  ];
  var SQUAD_APPLICATIONS = {
    2201: [
      { id: 9201, name: '张俊辉', phone: '136****1330', school: '广东医科大学', reason: '常住城区，方便就近参与', status: 0, time: '05-29 20:10' },
      { id: 9202, name: '陈嘉怡', phone: '138****5566', school: '雷州一中', reason: '想加入城区第一分队', status: 0, time: '05-30 08:30' },
      { id: 9203, name: '刘子墨', phone: '135****1234', school: '岭南师范学院', reason: '原分队解散，申请转入', status: 0, time: '05-30 19:00' },
    ],
    2203: [
      { id: 9211, name: '何静怡', phone: '137****9988', school: '雷州一中', reason: '家住附城镇', status: 0, time: '05-31 11:20' },
      { id: 9212, name: '李明轩', phone: '139****1122', school: '岭南师范学院', reason: '就近服务', status: 0, time: '05-31 13:05' },
    ],
  };

  /* 志愿者标记与授权（无志愿者列表接口，按 ID 定位；这里给少量已标记示例） */
  var FLAGGED_VOLUNTEERS = [
    { id: 9001, name: '周浩然', phone: '13911223344', school: '岭南师范学院', managerFlag: 1, grantedCodes: ['activity:manage', 'activity:leader-assign'], flagTime: '2025-10-09' },
    { id: 9004, name: '李明轩', phone: '13900011122', school: '岭南师范学院', managerFlag: 1, grantedCodes: ['activity:enroll-view'], flagTime: '2026-01-20' },
  ];

  /* 子账号 status:0停用 1启用 */
  var SUB_ACCOUNTS = [
    { id: 7, account: 'zuzhi01', name: '林海燕', dept: '组织部', status: 1, createTime: '2025-08-05', codes: ['activity:enroll-view', 'activity:enroll-audit', 'org:group-audit', 'org:squad-manage', 'org:squad-audit'] },
    { id: 9, account: 'mishu01', name: '吴敏', dept: '秘书部', status: 1, createTime: '2025-08-05', codes: ['activity:service-confirm', 'activity:points-grant', 'activity:attendance-edit'] },
    { id: 12, account: 'xuanchuan01', name: '黄梓萱', dept: '宣传部', status: 1, createTime: '2025-09-01', codes: ['pub:banner', 'pub:announcement', 'pub:file'] },
    { id: 15, account: 'jiancha01', name: '罗建华', dept: '监察部', status: 1, createTime: '2025-10-12', codes: ['activity:attendance-audit', 'activity:backfill-audit'] },
    { id: 18, account: 'zuzhi02', name: '杨雪', dept: '组织部', status: 0, createTime: '2026-02-18', codes: ['activity:enroll-view'] },
  ];

  /* 轮播图 linkType:0无 1网页 2小程序；status:0下架 1上架 */
  var BANNERS = [
    { id: 1, title: '2026 年度优秀志愿者表彰', imageUrl: '#grad1', linkType: 1, linkUrl: 'https://mp.weixin.qq.com/s/abc123', sort: 1, status: 1 },
    { id: 2, title: '暑期"七彩假期"志愿招募', imageUrl: '#grad2', linkType: 2, linkUrl: 'pages/activity/detail?id=1003', sort: 2, status: 1 },
    { id: 3, title: '无偿献血公益倡议', imageUrl: '#grad3', linkType: 0, linkUrl: '', sort: 3, status: 1 },
    { id: 4, title: '协会成立八周年回顾（草稿）', imageUrl: '#grad4', linkType: 1, linkUrl: 'https://mp.weixin.qq.com/s/xyz789', sort: 4, status: 0 },
  ];

  /* 公告 status:0草稿 1发布 */
  var ANNOUNCEMENTS = [
    { id: 1, title: '关于开展 2026 年志愿者注册信息核对的通知', summary: '请各分队组织成员于 6 月 20 日前完成信息核对。', linkType: 1, linkUrl: 'https://mp.weixin.qq.com/s/notice01', status: 1, time: '2026-05-28', views: 1240 },
    { id: 2, title: '雷州市恒德爱心公益协会换届选举公示', summary: '现将第三届理事会候选名单予以公示，公示期 7 天。', linkType: 0, linkUrl: '', status: 1, time: '2026-05-20', views: 2156 },
    { id: 3, title: '夏季活动防暑安全提示', summary: '高温时段户外活动注意事项，请负责人传达到每位志愿者。', linkType: 0, linkUrl: '', status: 1, time: '2026-05-15', views: 890 },
    { id: 4, title: '志愿服务时长认定办法（修订稿·草稿）', summary: '拟对时长与积分折算规则进行调整，征求意见中。', linkType: 0, linkUrl: '', status: 0, time: '2026-05-30', views: 0 },
  ];

  /* 文件下载 */
  var PUB_FILES = [
    { id: 1, name: '志愿者注册登记表.xlsx', size: '32 KB', type: 'xlsx', downloadable: 1, downloads: 326, time: '2026-03-01' },
    { id: 2, name: '协会章程（2026修订）.pdf', size: '1.2 MB', type: 'pdf', downloadable: 1, downloads: 512, time: '2026-04-10' },
    { id: 3, name: '活动安全责任承诺书.docx', size: '48 KB', type: 'docx', downloadable: 1, downloads: 198, time: '2026-04-22' },
    { id: 4, name: '财务公开报表（2025年度）.pdf', size: '860 KB', type: 'pdf', downloadable: 0, downloads: 0, time: '2026-05-18' },
  ];

  /* 志愿者列表（GET /a/user/volunteers）status:0停用 1正常；political 政治面貌 */
  var VOLUNTEERS = [
    { id: 9001, name: '周浩然', gender: '男', phone: '13911223344', idTail: '3344', school: '岭南师范学院', grade: '大三', political: '共青团员', squad: '城区第一分队', group: '西湖环保骑士', managerFlag: 1, status: 1, joinDate: '2025-03-12', hours: 86.5, points: 173, activities: 24, emergency: '周建国 13800001111' },
    { id: 9004, name: '李明轩', gender: '男', phone: '13900011122', idTail: '1122', school: '岭南师范学院', grade: '大二', political: '群众', squad: '附城镇分队', group: '暖阳助学小队', managerFlag: 1, status: 1, joinDate: '2025-06-20', hours: 52.0, points: 104, activities: 16, emergency: '李红梅 13700002222' },
    { id: 9011, name: '王雅琪', gender: '女', phone: '13750991022', idTail: '1022', school: '岭南师范学院', grade: '大三', political: '中共党员', squad: '附城镇分队', group: '暖阳助学小队', managerFlag: 0, status: 1, joinDate: '2025-02-08', hours: 120.0, points: 240, activities: 33, emergency: '王立军 13600003333' },
    { id: 9012, name: '李文博', gender: '男', phone: '13902460111', idTail: '0111', school: '岭南师范学院', grade: '研一', political: '中共党员', squad: '城区第一分队', group: '城西助老先锋队', managerFlag: 1, status: 1, joinDate: '2024-11-15', hours: 156.5, points: 313, activities: 41, emergency: '李国华 13500004444' },
    { id: 9013, name: '张俊辉', gender: '男', phone: '13602331330', idTail: '1330', school: '广东医科大学', grade: '大四', political: '共青团员', squad: '城区第一分队', group: '—', managerFlag: 0, status: 1, joinDate: '2025-09-01', hours: 18.0, points: 36, activities: 5, emergency: '张伟 13400005555' },
    { id: 9014, name: '陈嘉怡', gender: '女', phone: '13822445566', idTail: '5566', school: '雷州一中', grade: '高二', political: '共青团员', squad: '城区第一分队', group: '雷城交通文明岗小队', managerFlag: 0, status: 1, joinDate: '2026-01-10', hours: 8.0, points: 16, activities: 3, emergency: '陈志强 13300006666' },
    { id: 9015, name: '黄思远', gender: '男', phone: '13312340099', idTail: '0099', school: '广东海洋大学', grade: '大三', political: '群众', squad: '—', group: '海洋大学环保社志愿组', managerFlag: 0, status: 1, joinDate: '2025-10-22', hours: 24.5, points: 45, activities: 7, emergency: '黄海 13200007777' },
    { id: 9016, name: '何静怡', gender: '女', phone: '13755009988', idTail: '9988', school: '雷州一中', grade: '高三', political: '共青团员', squad: '附城镇分队', group: '—', managerFlag: 0, status: 1, joinDate: '2025-12-03', hours: 12.0, points: 24, activities: 4, emergency: '何明 13100008888' },
    { id: 9017, name: '刘子墨', gender: '男', phone: '13509981234', idTail: '1234', school: '岭南师范学院', grade: '大一', political: '共青团员', squad: '城区第一分队', group: '城西助老先锋队', managerFlag: 0, status: 1, joinDate: '2026-02-14', hours: 6.0, points: 9, activities: 2, emergency: '刘强 13000009999' },
    { id: 9018, name: '吴佳颖', gender: '女', phone: '13755667788', idTail: '7788', school: '雷州二中', grade: '高二', political: '共青团员', squad: '城区第二分队', group: '—', managerFlag: 0, status: 0, joinDate: '2025-11-30', hours: 4.0, points: 8, activities: 1, emergency: '吴勇 13912340000' },
    { id: 9019, name: '郑伟杰', gender: '男', phone: '13688990011', idTail: '0011', school: '广东医科大学', grade: '大四', political: '群众', squad: '东里镇分队', group: '应急救援互助组', managerFlag: 0, status: 0, joinDate: '2025-05-18', hours: 64.0, points: 110, activities: 19, emergency: '郑国强 13898765432' },
  ];

  /* 活动留言（志愿者端发表 /v/.../messages；管理端可下架 DELETE /a/activity/messages/{id}） */
  var ACTIVITY_MESSAGES = {
    1002: [
      { id: 71, name: '周浩然', text: '请问需要自带手套和垃圾袋吗？', time: '05-30 12:10' },
      { id: 72, name: '吴佳颖', text: '集合点具体在北门哪个位置呀～', time: '05-30 18:42' },
      { id: 73, name: '匿名用户', text: '广告：XX培训班招生……', time: '05-31 09:05', spam: true },
    ],
    1003: [
      { id: 81, name: '王雅琪', text: '小朋友们都很可爱，下次还想参加！', time: '05-28 11:30' },
    ],
  };

  w.HD = {
    PERM_CATALOG: PERM_CATALOG, ALL_CODES: ALL_CODES, VOLUNTEER_GRANTABLE: VOLUNTEER_GRANTABLE,
    IDENTITIES: IDENTITIES, hasPerm: hasPerm,
    ACTIVITIES: ACTIVITIES, PENDING_ACTIVITIES: PENDING_ACTIVITIES, ENROLLMENTS: ENROLLMENTS, ATTENDANCES: ATTENDANCES, LEADERS: LEADERS, VIOLATIONS: VIOLATIONS,
    SERVICE_RECORDS: SERVICE_RECORDS, ATTENDANCE_CHANGES: ATTENDANCE_CHANGES, BACKFILLS: BACKFILLS,
    GROUPS: GROUPS, GROUP_APPLICATIONS: GROUP_APPLICATIONS, GROUP_LEADER_HISTORY: GROUP_LEADER_HISTORY,
    SQUADS: SQUADS, SQUAD_APPLICATIONS: SQUAD_APPLICATIONS,
    FLAGGED_VOLUNTEERS: FLAGGED_VOLUNTEERS, SUB_ACCOUNTS: SUB_ACCOUNTS, VOLUNTEERS: VOLUNTEERS,
    BANNERS: BANNERS, ANNOUNCEMENTS: ANNOUNCEMENTS, PUB_FILES: PUB_FILES,
    ACTIVITY_MESSAGES: ACTIVITY_MESSAGES,
  };
})(window);
