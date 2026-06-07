const navItems = [
  { key: "dashboard", label: "首页看板" },
  { key: "activities", label: "活动管理" },
  { key: "publicity", label: "轮播公告" },
  { key: "organization", label: "组织管理" },
  { key: "volunteers", label: "志愿者管理" },
  { key: "operations", label: "考勤补录" },
  { key: "permissions", label: "子账号权限" }
];

const api = window.HENGDE_API_ENDPOINTS;
const apiBaseUrl = api.baseUrl || api.basePath;
const authLoginUrl = `${apiBaseUrl}${api.admin.auth.login}`;
const authLogoutUrl = `${apiBaseUrl}${api.admin.auth.logout}`;

const mockRows = {
  metrics: [
    ["注册志愿者", "1286", "实名志愿者人数"],
    ["活动场次", "342", "已发布活动"],
    ["服务时长", "18920", "累计小时"],
    ["待审核", "17", "小组/分队/报名"]
  ],
  activities: [
    { id: 1, activityNo: "HD202606001", title: "端午节关爱老人志愿服务", startTime: "2026-06-10T08:30:00", quota: 30, status: 0 },
    { id: 2, activityNo: "HD202606002", title: "文明交通劝导行动", startTime: "2026-06-15T17:00:00", quota: 20, status: 1 },
    { id: 3, activityNo: "HD202606003", title: "爱心助学资料整理", startTime: "2026-06-18T14:30:00", quota: 12, status: 2 }
  ],
  banners: [
    { id: 1, title: "雷州公益同行", imageUrl: "https://example.com/banner.jpg", linkType: 2, linkUrl: "/pages/activity/list", sort: 1, status: 1 }
  ],
  announcements: [
    { id: 1, title: "实名注册通知", summary: "请志愿者完成实名注册", coverImageUrl: "https://example.com/notice.jpg", linkType: 1, linkUrl: "https://mp.weixin.qq.com/", status: 1 }
  ],
  groups: [
    { id: 1, groupNo: "G202605001", name: "萤火志愿小组", leaderName: "林可", memberCount: 18, status: 1 },
    { id: 2, groupNo: "G202605002", name: "向阳服务队", leaderName: "吴嘉", memberCount: 11, status: 0 }
  ],
  squads: [
    { id: 1, type: "中学分队", name: "雷州市第一中学分队", leaderName: "邝大程", leaderPhone: "15766508094", memberCount: 126, visibleFields: "姓名/学校/手机号", status: 1 },
    { id: 2, type: "乡镇分队", name: "南兴镇分队", leaderName: "黄灵媛", leaderPhone: "15766508094", memberCount: 84, visibleFields: "姓名/学校/手机号", status: 1 }
  ],
  volunteers: [
    { id: 1, realName: "邝大程", phone: "15766508094", school: "雷州市第一中学", grade: "高三", squadName: "雷州市第一中学分队", roleName: "管理团队", managerFlag: 1, permissionIds: [1] },
    { id: 2, realName: "黄灵媛", phone: "15766508094", school: "雷州市第二中学", grade: "高一", squadName: "南兴镇分队", roleName: "志愿者" }
  ],
  volunteerGrantablePermissions: [
    { id: 1, code: "activity:publish", name: "发布活动" },
    { id: 2, code: "activity:manage", name: "管理活动" }
  ],
  subAccounts: [
    { id: 1, username: "admin", realName: "超管", department: "组织部", status: 1 }
  ],
  attendanceChanges: [],
  backfills: []
};

const state = {
  active: "dashboard",
  apiMode: localStorage.getItem("hengdeAdminUseRealApi") === "true",
  token: localStorage.getItem("hengdeAdminToken") || "",
  operator: localStorage.getItem("hengdeAdminOperator") || "",
  data: JSON.parse(JSON.stringify(mockRows)),
  apiResult: null
};

const apiConsoleGroups = [
  {
    title: "活动报名与负责人",
    items: [
      ["GET", "/a/activity/activities/{id}/enrollments", "报名列表", { id: "活动ID" }, {}, { page: 1, size: 20, status: "" }],
      ["POST", "/a/activity/activities/{id}/enrollments", "手动新增报名", { id: "活动ID" }, { volunteerId: 1, slotIds: [1] }],
      ["GET", "/a/activity/activities/{id}/enrollments/export", "导出报名名单", { id: "活动ID" }, {}],
      ["POST", "/a/activity/enrollments/{id}/approve", "报名审核通过", { id: "报名ID" }, {}],
      ["POST", "/a/activity/enrollments/{id}/reject", "报名审核拒绝", { id: "报名ID" }, { reason: "不符合报名要求" }],
      ["DELETE", "/a/activity/enrollments/{id}", "删除报名记录", { id: "报名ID" }, {}],
      ["POST", "/a/activity/activities/{id}/leaders", "指派活动负责人", { id: "活动ID" }, { leaderType: 1, refId: 1 }],
      ["GET", "/a/activity/activities/{id}/leaders", "负责人列表", { id: "活动ID" }, {}],
      ["DELETE", "/a/activity/activities/{id}/leaders/{leaderId}", "取消负责人", { id: "活动ID", leaderId: "负责人ID" }, {}],
      ["POST", "/a/activity/activities/{id}/start", "活动开始", { id: "活动ID" }, {}],
      ["POST", "/a/activity/activities/{id}/finish", "活动结束", { id: "活动ID" }, {}],
      ["POST", "/a/activity/activities/{id}/check-outs", "统一签退", { id: "活动ID" }, { volunteerIds: [] }]
    ]
  },
  {
    title: "考勤、积分、补录",
    items: [
      ["PATCH", "/a/activity/activities/{id}/attendances/{volunteerId}", "标记到位/确认签到", { id: "活动ID", volunteerId: "志愿者ID" }, { attendStatus: 1 }],
      ["POST", "/a/activity/activities/{id}/attendances/{volunteerId}/violations", "记录违规", { id: "活动ID", volunteerId: "志愿者ID" }, { type: 1, remark: "玩手机" }],
      ["POST", "/a/activity/activities/{id}/summary", "上传活动总结", { id: "活动ID" }, { content: "活动总结", imageUrls: [] }],
      ["GET", "/a/activity/service-records", "服务记录大板块", {}, {}, { page: 1, size: 20, activityId: "", volunteerId: "", status: "" }],
      ["GET", "/a/activity/service-records/pending", "待秘书部确认", {}, {}, { page: 1, size: 20 }],
      ["POST", "/a/activity/attendances/{id}/confirm", "秘书部确认时长", { id: "考勤ID" }, {}],
      ["POST", "/a/activity/attendances/{id}/points", "发放积分", { id: "考勤ID" }, { points: 10 }],
      ["POST", "/a/activity/attendances/{id}/changes", "申请改考勤/积分", { id: "考勤ID" }, { changeType: 3, newValue: "10", reason: "修正积分" }],
      ["GET", "/a/activity/attendance-changes", "变更申请列表", {}, {}, { page: 1, size: 20, status: "" }],
      ["POST", "/a/activity/activities/{id}/backfills", "活动补录申请", { id: "活动ID" }, { idCard: "", phone: "15766508094", name: "黄灵媛", slotId: 1, reason: "历史数据补录" }],
      ["GET", "/a/activity/backfills", "补录申请列表", {}, {}, { page: 1, size: 20, status: "" }],
      ["DELETE", "/a/activity/messages/{id}", "删除活动留言", { id: "留言ID" }, {}]
    ]
  },
  {
    title: "组织、小组、分队",
    items: [
      ["GET", "/a/organization/groups/applications", "建组申请列表", {}, {}, { page: 1, size: 20, status: "" }],
      ["POST", "/a/organization/groups/applications/{id}/approve", "批准建组", { id: "申请ID" }, { reason: "同意" }],
      ["POST", "/a/organization/groups/applications/{id}/reject", "拒绝建组", { id: "申请ID" }, { reason: "资料不完整" }],
      ["GET", "/a/organization/groups/{id}/leader-history", "组长变更历史", { id: "小组ID" }, {}],
      ["GET", "/a/organization/squads/{id}/applications", "分队加入申请", { id: "分队ID" }, {}, { page: 1, size: 20, status: "" }],
      ["POST", "/a/organization/squads/applications/{id}/approve", "批准加入分队", { id: "申请ID" }, { reason: "同意" }],
      ["POST", "/a/organization/squads/applications/{id}/reject", "拒绝加入分队", { id: "申请ID" }, { reason: "不符合条件" }],
      ["PUT", "/a/organization/volunteers/{id}/manager-flag", "设置管理团队标记", { id: "志愿者ID" }, { flag: 1 }],
      ["GET", "/a/organization/permissions/volunteer-grantable", "可授权给志愿者的权限", {}, {}],
      ["GET", "/a/organization/volunteers/{id}/permissions", "查看志愿者权限", { id: "志愿者ID" }, {}],
      ["PUT", "/a/organization/volunteers/{id}/permissions", "替换志愿者权限", { id: "志愿者ID" }, { permissionIds: [] }]
    ]
  },
  {
    title: "子账号、用户、公示文件",
    items: [
      ["GET", "/a/organization/sub-accounts", "子账号列表", {}, {}, { page: 1, size: 20 }],
      ["POST", "/a/organization/sub-accounts", "创建子账号", {}, { username: "org01", password: "123456", realName: "组织部账号", phone: "15766508094", department: "组织部" }],
      ["PUT", "/a/organization/sub-accounts/{id}", "修改子账号", { id: "子账号ID" }, { realName: "组织部账号", phone: "15766508094", department: "组织部" }],
      ["DELETE", "/a/organization/sub-accounts/{id}", "删除子账号", { id: "子账号ID" }, {}],
      ["PUT", "/a/organization/sub-accounts/{id}/permissions", "替换权限集合", { id: "子账号ID" }, { permissionIds: [] }],
      ["POST", "/a/organization/sub-accounts/{id}/password/reset", "重置子账号密码", { id: "子账号ID" }, { newPassword: "123456" }],
      ["GET", "/a/organization/permissions", "权限点列表", {}, {}],
      ["GET", "/a/user/volunteers/export", "导出志愿者", {}, {}],
      ["PATCH", "/a/user/volunteers/{id}/status", "暂停/恢复志愿者", { id: "志愿者ID" }, { status: 1 }],
      ["DELETE", "/a/user/volunteers/{id}", "删除志愿者", { id: "志愿者ID" }, {}],
      ["POST", "/a/user/volunteers/{id}/password/reset", "重置志愿者密码", { id: "志愿者ID" }, {}],
      ["GET", "/a/publicity/files", "文件列表", {}, {}, { page: 1, size: 20 }],
      ["POST", "/a/publicity/files", "新增文件下载", {}, { title: "测试文件", fileUrl: "https://example.com/file.pdf", downloadable: true }],
      ["PATCH", "/a/publicity/files/{id}/access", "开放/关闭下载", { id: "文件ID" }, { downloadable: true }],
      ["DELETE", "/a/publicity/files/{id}", "删除文件", { id: "文件ID" }, {}],
      ["PATCH", "/a/publicity/banners/{id}/sort", "调整轮播排序", { id: "轮播图ID" }, { sort: 1 }]
    ]
  }
];

const nav = document.querySelector("#nav");
const content = document.querySelector("#content");
const pageTitle = document.querySelector("#page-title");
const loginUrl = document.querySelector("#admin-login-url");
const loginStatus = document.querySelector("#admin-login-status");
const loginButton = document.querySelector("#admin-login-btn");
const logoutButton = document.querySelector("#admin-logout-btn");
const apiModeButton = document.querySelector("#api-mode-btn");
const accountInput = document.querySelector("#admin-account");
const passwordInput = document.querySelector("#admin-password");
const operatorLabel = document.querySelector("#operator-label");
const modalRoot = document.querySelector("#modal-root");
const toastNode = document.querySelector("#toast");

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function payloadOf(result) {
  if (!result || typeof result !== "object") return result;
  return result.data !== undefined ? result.data : result;
}

function recordsOf(result) {
  const payload = payloadOf(result);
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== "object") return [];
  if (Array.isArray(payload.records)) return payload.records;
  if (Array.isArray(payload.list)) return payload.list;
  if (Array.isArray(payload.items)) return payload.items;
  if (Array.isArray(payload.rows)) return payload.rows;
  if (payload.data !== undefined && payload.data !== payload) return recordsOf(payload);
  return [];
}

function valueOf(row, keys, fallback = "") {
  for (const key of keys) {
    if (row && row[key] !== undefined && row[key] !== null && row[key] !== "") return row[key];
  }
  return fallback;
}

function dateTimeText(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 16);
}

function normalizeDateTime(value) {
  if (!value) return null;
  return value.length === 16 ? `${value}:00` : value;
}

function numberOrNull(value) {
  if (value === "" || value === undefined || value === null) return null;
  const number = Number(value);
  return Number.isNaN(number) ? null : number;
}

function splitList(value, mapper = (item) => item) {
  return String(value || "")
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map(mapper);
}

function showToast(message, type = "info") {
  toastNode.textContent = message;
  toastNode.className = `toast show ${type}`;
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    toastNode.className = "toast";
  }, 2600);
}

function badge(text) {
  const label = String(text ?? "");
  if (["待审核", "审核中", "草稿", "未开放", "待审"].includes(label)) return `<span class="badge warn">${escapeHtml(label)}</span>`;
  if (["已拒绝", "禁用", "报名截止", "下架"].includes(label)) return `<span class="badge danger">${escapeHtml(label)}</span>`;
  if (["管理团队", "轮播图", "活动中"].includes(label)) return `<span class="badge blue">${escapeHtml(label)}</span>`;
  return `<span class="badge">${escapeHtml(label || "正常")}</span>`;
}

function statusText(value, map, fallback = "正常") {
  if (value === undefined || value === null || value === "") return fallback;
  return map[value] || map[String(value)] || String(value);
}

function renderNav() {
  nav.innerHTML = navItems.map((item) => `
    <button class="${item.key === state.active ? "active" : ""}" data-key="${item.key}">${item.label}</button>
  `).join("");
}

function table(headers, rows, renderRow, emptyText = "暂无数据") {
  return `
    <div class="table-panel">
      <table>
        <thead><tr>${headers.map((item) => `<th>${item}</th>`).join("")}</tr></thead>
        <tbody>
          ${rows.length ? rows.map(renderRow).join("") : `<tr><td colspan="${headers.length}" class="empty-cell">${emptyText}</td></tr>`}
        </tbody>
      </table>
    </div>
  `;
}

function findApiConsoleItem(label) {
  for (const group of apiConsoleGroups) {
    const item = group.items.find((entry) => entry[2] === label);
    if (item) return item;
  }
  return null;
}

function apiActionButton(label) {
  const item = findApiConsoleItem(label);
  if (!item) return "";
  const [method, path, itemLabel, params, body, query] = item;
  return `
    <button
      class="button compact console-action ${method.toLowerCase()}"
      data-action="open-api-call"
      data-method="${method}"
      data-path="${escapeHtml(path)}"
      data-label="${escapeHtml(itemLabel)}"
      data-params="${escapeHtml(JSON.stringify(params || {}))}"
      data-body="${escapeHtml(JSON.stringify(body || {}))}"
      data-query="${escapeHtml(JSON.stringify(query || {}))}"
    >${escapeHtml(itemLabel)}</button>
  `;
}

function apiActionGroup(title, labels) {
  const buttons = labels.map(apiActionButton).filter(Boolean).join("");
  if (!buttons) return "";
  return `
    <div class="integrated-tools">
      <div class="tool-title">${escapeHtml(title)}</div>
      <div class="console-actions">${buttons}</div>
    </div>
  `;
}

function renderApiResult() {
  if (!state.apiResult) return "";
  return `
    <div class="api-result-card">
      <div class="api-result-head">
        <div>
          <strong>${escapeHtml(state.apiResult.label)}</strong>
          <div class="endpoint-line">${escapeHtml(state.apiResult.method)} ${escapeHtml(state.apiResult.path)}</div>
        </div>
        <div class="actions">
          <span class="badge ${state.apiResult.ok ? "" : "danger"}">${state.apiResult.ok ? "成功" : "失败"}</span>
          ${actionButton("清空结果", "clear-api-result")}
        </div>
      </div>
      <pre>${escapeHtml(JSON.stringify(state.apiResult.result, null, 2))}</pre>
    </div>
  `;
}

function actionButton(label, action, extra = "") {
  let className = "button compact";
  let attrs = extra;
  const classMatch = extra.match(/class="([^"]+)"/);
  if (classMatch) {
    className = classMatch[1];
    attrs = extra.replace(classMatch[0], "");
  }
  return `<button class="${className}" data-action="${action}" ${attrs}>${label}</button>`;
}

function rowId(row) {
  return escapeHtml(valueOf(row, ["id", "activityId", "groupId", "squadId"], ""));
}

function findRow(collection, id) {
  return (state.data[collection] || []).find((row) => String(valueOf(row, ["id", "activityId", "groupId", "squadId"], "")) === String(id));
}

function activityStatus(row) {
  return statusText(valueOf(row, ["statusName", "statusText", "status"], ""), {
    0: "未开放",
    1: "报名中",
    2: "报名截止",
    3: "活动中",
    4: "已结束",
    DRAFT: "未开放",
    PUBLISHED: "报名中",
    CLOSED: "报名截止",
    IN_PROGRESS: "活动中",
    FINISHED: "已结束"
  }, "未开放");
}

function renderDashboard() {
  const metrics = state.data.metrics.length ? state.data.metrics : mockRows.metrics;
  return `
    <div class="grid metrics">
      ${metrics.map(([label, value, hint]) => `
        <div class="metric">
          <div class="metric-value">${escapeHtml(value)}</div>
          <div class="metric-label">${escapeHtml(label)} · ${escapeHtml(hint)}</div>
        </div>
      `).join("")}
    </div>
    <div class="grid two" style="margin-top:16px">
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">快速操作</h2>
          <span class="muted">${state.apiMode ? "真实接口模式" : "Mock 预览模式"}</span>
        </div>
        <div class="quick-actions">
          ${actionButton("发布活动", "open-activity-modal", "class=\"button primary\"")}
          ${actionButton("新增轮播图", "open-banner-modal")}
          ${actionButton("新增公告", "open-announcement-modal")}
          ${actionButton("创建分队", "open-squad-modal")}
        </div>
      </section>
      <section class="panel">
        <h2 class="panel-title">联调提示</h2>
        <div class="tips">
          <div>1. 真实接口模式下先登录，再创建活动/公告/轮播图。</div>
          <div>2. 微信开发者工具中把小程序 useMockApi 改为 false。</div>
          <div>3. 志愿者端使用 /v/auth/login/dev，无需 AppID。</div>
        </div>
      </section>
    </div>
  `;
}

function renderActivities() {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">活动管理</h2>
        <div class="actions">
          ${actionButton("刷新", "refresh")}
          ${actionButton("新增活动", "open-activity-modal", "class=\"button primary compact\"")}
          ${actionButton("周期发布", "open-recurring-modal")}
          ${actionButton("历史活动", "open-historical-modal")}
        </div>
      </div>
      ${apiActionGroup("报名与负责人", [
        "手动新增报名",
        "报名审核通过",
        "报名审核拒绝",
        "删除报名记录",
        "指派活动负责人",
        "负责人列表",
        "取消负责人",
        "活动开始",
        "活动结束",
        "统一签退"
      ])}
      ${table(["编号", "活动名称", "开始时间", "人数", "状态", "操作"], state.data.activities, (row) => `
        <tr>
          <td>${escapeHtml(valueOf(row, ["activityNo", "no", "code", "id"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["title", "name", "activityName"], "未命名活动"))}</td>
          <td>${escapeHtml(dateTimeText(valueOf(row, ["startTime", "activityStartTime", "beginTime"], "")))}</td>
          <td>${escapeHtml(valueOf(row, ["quota", "needCount", "demandCount", "capacity"], 0))}</td>
          <td>${badge(activityStatus(row))}</td>
          <td class="actions">
            ${actionButton("编辑", "edit-activity", `data-id="${rowId(row)}"`)}
            ${actionButton("复制", "copy-activity", `data-id="${rowId(row)}"`)}
            ${actionButton("删除", "delete-activity", `data-id="${rowId(row)}"`)}
            ${actionButton("报名列表", "load-enrollments", `data-id="${rowId(row)}"`)}
          </td>
        </tr>
      `)}
      ${renderApiResult()}
    </section>
  `;
}

function renderPublicity() {
  return `
    <div class="grid two">
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">轮播图</h2>
          <div class="actions">
            ${actionButton("刷新", "refresh")}
            ${actionButton("新增轮播图", "open-banner-modal", "class=\"button primary compact\"")}
          </div>
        </div>
        ${apiActionGroup("轮播图补充操作", ["调整轮播排序"])}
        ${table(["标题", "图片", "跳转", "排序", "状态", "操作"], state.data.banners, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["title"], ""))}</td>
            <td class="ellipsis">${escapeHtml(valueOf(row, ["imageUrl", "coverImageUrl"], ""))}</td>
            <td>${escapeHtml(linkText(row))}</td>
            <td>${escapeHtml(valueOf(row, ["sort"], ""))}</td>
            <td>${badge(statusText(valueOf(row, ["status"], ""), { 0: "下架", 1: "启用" }, "启用"))}</td>
            <td class="actions">
              ${actionButton("编辑", "edit-banner", `data-id="${rowId(row)}"`)}
              ${actionButton("删除", "delete-banner", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">公告栏</h2>
          <div class="actions">${actionButton("新增公告", "open-announcement-modal", "class=\"button primary compact\"")}</div>
        </div>
        ${apiActionGroup("公示文件", ["文件列表", "新增文件下载", "开放/关闭下载", "删除文件"])}
        ${table(["标题", "摘要", "封面", "跳转", "状态", "操作"], state.data.announcements, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["title"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["summary"], ""))}</td>
            <td class="ellipsis">${escapeHtml(valueOf(row, ["coverImageUrl", "imageUrl"], ""))}</td>
            <td>${escapeHtml(linkText(row))}</td>
            <td>${badge(statusText(valueOf(row, ["status"], ""), { 0: "草稿", 1: "启用" }, "启用"))}</td>
            <td class="actions">
              ${actionButton("编辑", "edit-announcement", `data-id="${rowId(row)}"`)}
              ${actionButton("删除", "delete-announcement", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      ${renderApiResult()}
    </div>
  `;
}

function linkText(row) {
  const type = Number(valueOf(row, ["linkType"], 0));
  if (type === 1) return "公众号推文";
  if (type === 2) return "小程序页面";
  return "不跳转";
}

function renderOrganization() {
  return `
    <div class="grid two">
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">志愿小组</h2>
          <div class="actions">${actionButton("刷新", "refresh")}</div>
        </div>
        ${apiActionGroup("建组审核", ["建组申请列表", "批准建组", "拒绝建组", "组长变更历史"])}
        ${table(["编号", "小组名称", "组长", "成员数", "状态", "操作"], state.data.groups, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["groupNo", "no", "id"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["name", "groupName"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["leaderName", "leader"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["memberCount", "members"], 0))}</td>
            <td>${badge(statusText(valueOf(row, ["statusName", "statusText", "status"], ""), { 0: "待审核", 1: "已通过", 2: "已拒绝", 3: "已解散" }, "待审核"))}</td>
            <td class="actions">
              ${actionButton("组长转移", "transfer-group", `data-id="${rowId(row)}"`)}
              ${actionButton("解散", "delete-group", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">归属分队</h2>
          <div class="actions">${actionButton("创建分队", "open-squad-modal", "class=\"button primary compact\"")}</div>
        </div>
        ${apiActionGroup("分队申请审核", ["分队加入申请", "批准加入分队", "拒绝加入分队"])}
        ${table(["类型", "分队名称", "负责人", "联系方式", "成员数", "操作"], state.data.squads, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["type", "squadType"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["name", "squadName"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["leaderName", "leader"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["leaderPhone", "phone"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["memberCount", "members"], 0))}</td>
            <td class="actions">
              ${actionButton("编辑", "edit-squad", `data-id="${rowId(row)}"`)}
              ${actionButton("删除", "delete-squad", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      ${renderApiResult()}
    </div>
  `;
}

function renderVolunteers() {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">志愿者管理</h2>
        <div class="actions">${actionButton("刷新", "refresh")}</div>
      </div>
      ${apiActionGroup("账号与权限", [
        "导出志愿者",
        "暂停/恢复志愿者",
        "删除志愿者",
        "重置志愿者密码",
        "设置管理团队标记",
        "可授权给志愿者的权限",
        "查看志愿者权限",
        "替换志愿者权限"
      ])}
      ${table(["姓名", "手机号", "学校", "年级", "归属组织", "角色", "操作"], state.data.volunteers, (row) => `
        <tr>
          <td>${escapeHtml(valueOf(row, ["realName", "name"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["phone", "mobile"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["school"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["grade"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["squadName", "squad"], "未归属"))}</td>
          <td>${badge(statusText(valueOf(row, ["roleName", "role", "status"], ""), { visitor: "游客", volunteer: "志愿者", admin: "管理团队" }, "志愿者"))}</td>
          <td class="actions">
            ${actionButton("管理团队/权限", "open-volunteer-permissions", `data-id="${rowId(row)}"`)}
          </td>
        </tr>
      `)}
      ${renderApiResult()}
    </section>
  `;
}

function renderOperations() {
  return `
    <div class="grid two">
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">考勤/积分变更</h2>
          <div class="actions">${actionButton("刷新", "refresh")}</div>
        </div>
        ${apiActionGroup("考勤与积分", [
          "标记到位/确认签到",
          "记录违规",
          "上传活动总结",
          "服务记录大板块",
          "待秘书部确认",
          "秘书部确认时长",
          "发放积分",
          "申请改考勤/积分",
          "变更申请列表",
          "删除活动留言"
        ])}
        ${table(["ID", "活动", "志愿者", "状态", "操作"], state.data.attendanceChanges, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["id"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["activityTitle", "activityName"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["volunteerName", "name"], ""))}</td>
            <td>${badge(statusText(valueOf(row, ["status"], ""), { 0: "待审", 1: "已通过", 2: "已拒绝" }, "待审"))}</td>
            <td class="actions">
              ${actionButton("通过", "approve-attendance-change", `data-id="${rowId(row)}"`)}
              ${actionButton("拒绝", "reject-attendance-change", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2 class="panel-title">活动补录</h2>
          <div class="actions">${actionButton("补录申请", "open-backfill-modal", "class=\"button primary compact\"")}</div>
        </div>
        ${apiActionGroup("补录审核", ["活动补录申请", "补录申请列表"])}
        ${table(["ID", "活动", "志愿者", "状态", "操作"], state.data.backfills, (row) => `
          <tr>
            <td>${escapeHtml(valueOf(row, ["id"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["activityTitle", "activityName"], ""))}</td>
            <td>${escapeHtml(valueOf(row, ["volunteerName", "name"], ""))}</td>
            <td>${badge(statusText(valueOf(row, ["status"], ""), { 0: "待审", 1: "已通过", 2: "已拒绝" }, "待审"))}</td>
            <td class="actions">
              ${actionButton("通过", "approve-backfill", `data-id="${rowId(row)}"`)}
              ${actionButton("拒绝", "reject-backfill", `data-id="${rowId(row)}"`)}
            </td>
          </tr>
        `)}
      </section>
      ${renderApiResult()}
    </div>
  `;
}

function renderPermissions() {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">子账号权限</h2>
        <div class="actions">${actionButton("刷新", "refresh")}</div>
      </div>
      ${apiActionGroup("子账号操作", [
        "子账号列表",
        "创建子账号",
        "修改子账号",
        "删除子账号",
        "替换权限集合",
        "重置子账号密码",
        "权限点列表"
      ])}
      ${table(["账号", "姓名", "部门", "状态"], state.data.subAccounts, (row) => `
        <tr>
          <td>${escapeHtml(valueOf(row, ["username", "account"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["realName", "name"], ""))}</td>
          <td>${escapeHtml(valueOf(row, ["department", "deptName"], ""))}</td>
          <td>${badge(statusText(valueOf(row, ["status"], ""), { 0: "禁用", 1: "正常" }, "正常"))}</td>
        </tr>
      `)}
      ${renderApiResult()}
    </section>
  `;
}

const views = {
  dashboard: { title: "首页看板", render: renderDashboard },
  activities: { title: "活动管理", render: renderActivities },
  publicity: { title: "轮播公告", render: renderPublicity },
  organization: { title: "组织管理", render: renderOrganization },
  volunteers: { title: "志愿者管理", render: renderVolunteers },
  operations: { title: "考勤补录", render: renderOperations },
  permissions: { title: "子账号权限", render: renderPermissions }
};

function render() {
  const view = views[state.active] || views.dashboard;
  if (!views[state.active]) state.active = "dashboard";
  pageTitle.textContent = view.title;
  content.innerHTML = view.render();
  renderNav();
  updateChrome();
}

function updateChrome() {
  loginUrl.textContent = `POST ${authLoginUrl}`;
  apiModeButton.textContent = state.apiMode ? "真实接口" : "Mock 数据";
  apiModeButton.classList.toggle("primary", state.apiMode);
  operatorLabel.textContent = state.token ? `${state.operator || "管理团队"} · 已登录` : "未登录";
  loginStatus.textContent = state.token ? `token 已保存，当前 ${state.apiMode ? "真实接口" : "Mock"} 模式` : "未登录";
}

async function requestAdmin(path, options = {}) {
  const headers = Object.assign({ "Content-Type": "application/json" }, options.headers || {});
  if (state.token) headers.Authorization = state.token;
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const text = await response.text();
  let result = {};
  try {
    result = text ? JSON.parse(text) : {};
  } catch (error) {
    result = { raw: text };
  }
  if (!response.ok) {
    throw new Error(result.message || result.msg || `HTTP ${response.status}`);
  }
  if (result && typeof result === "object" && result.code !== undefined && ![0, 200].includes(Number(result.code))) {
    throw new Error(result.message || result.msg || `接口返回 code=${result.code}`);
  }
  return result;
}

async function downloadAdmin(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (state.token) headers.Authorization = state.token;
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: options.method || "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const result = await response.json();
      message = result.message || result.msg || message;
    } catch (error) {
      // 导出接口失败时返回体不一定是 JSON。
    }
    throw new Error(message);
  }
  const blob = await response.blob();
  const disposition = response.headers.get("Content-Disposition") || "";
  const match = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
  const filename = match ? decodeURIComponent(match[1]) : `hengde-export-${Date.now()}.xlsx`;
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  return { downloaded: true, filename, size: blob.size };
}

function readAdminToken(result) {
  const payload = payloadOf(result);
  if (typeof payload === "string") return payload;
  return payload?.token || payload?.accessToken || result?.token || result?.accessToken || "";
}

async function loadRemoteView(viewKey = state.active) {
  if (!state.apiMode) {
    state.data = JSON.parse(JSON.stringify(mockRows));
    render();
    return;
  }
  if (!state.token) {
    showToast("请先登录管理端", "warn");
    render();
    return;
  }
  try {
    if (viewKey === "dashboard") {
      state.data.metrics = mockRows.metrics;
    } else if (viewKey === "activities") {
      state.data.activities = recordsOf(await requestAdmin(api.admin.activities));
    } else if (viewKey === "publicity") {
      const [banners, announcements] = await Promise.all([
        requestAdmin(api.admin.banners),
        requestAdmin(api.admin.announcements)
      ]);
      state.data.banners = recordsOf(banners);
      state.data.announcements = recordsOf(announcements);
    } else if (viewKey === "organization") {
      const [groups, squads] = await Promise.all([
        requestAdmin(api.admin.groups),
        requestAdmin(api.admin.squads)
      ]);
      state.data.groups = recordsOf(groups);
      state.data.squads = recordsOf(squads);
    } else if (viewKey === "volunteers") {
      state.data.volunteers = recordsOf(await requestAdmin(api.admin.volunteers));
    } else if (viewKey === "operations") {
      const [changes, backfills] = await Promise.all([
        requestAdmin(api.admin.attendanceChanges),
        requestAdmin(api.admin.backfills)
      ]);
      state.data.attendanceChanges = recordsOf(changes);
      state.data.backfills = recordsOf(backfills);
    } else if (viewKey === "permissions") {
      state.data.subAccounts = recordsOf(await requestAdmin(api.admin.subAccounts));
    }
    showToast("数据已刷新", "success");
  } catch (error) {
    showToast(`真实接口加载失败：${error.message}`, "error");
  }
  render();
}

async function loginAdmin() {
  try {
    const username = accountInput.value.trim() || "admin";
    const password = passwordInput.value || "admin123";
    const result = await requestAdmin(api.admin.auth.login, {
      method: "POST",
      body: { username, account: username, password }
    });
    const token = readAdminToken(result);
    if (!token) throw new Error("登录成功但未返回 token");
    state.token = token;
    state.operator = username;
    localStorage.setItem("hengdeAdminToken", token);
    localStorage.setItem("hengdeAdminOperator", username);
    showToast("登录成功", "success");
    await loadRemoteView(state.active);
  } catch (error) {
    showToast(`登录失败：${error.message}`, "error");
    updateChrome();
  }
}

async function logoutAdmin() {
  if (state.apiMode && state.token) {
    try {
      await requestAdmin(api.admin.auth.logout, { method: "POST" });
    } catch (error) {
      // 退出时即使服务端不可用，也清理本地登录态。
    }
  }
  state.token = "";
  state.operator = "";
  localStorage.removeItem("hengdeAdminToken");
  localStorage.removeItem("hengdeAdminOperator");
  showToast("已退出", "success");
  render();
}

function field(name, label, value = "", attrs = "") {
  return `
    <label class="field">
      <span>${label}</span>
      <input name="${name}" value="${escapeHtml(value)}" ${attrs} />
    </label>
  `;
}

function hiddenField(name, value = "") {
  return `<input type="hidden" name="${name}" value="${escapeHtml(value)}" />`;
}

function textareaField(name, label, value = "", attrs = "") {
  return `
    <label class="field field-full">
      <span>${label}</span>
      <textarea name="${name}" ${attrs}>${escapeHtml(value)}</textarea>
    </label>
  `;
}

function selectField(name, label, options, selected = "") {
  return `
    <label class="field">
      <span>${label}</span>
      <select name="${name}">
        ${options.map(([value, text]) => `<option value="${value}" ${String(value) === String(selected) ? "selected" : ""}>${text}</option>`).join("")}
      </select>
    </label>
  `;
}

function helpText(text) {
  return `<div class="field-help field-full">${escapeHtml(text)}</div>`;
}

function friendlyLabel(key) {
  const labels = {
    volunteerId: "志愿者ID",
    slotIds: "场次ID",
    slotId: "场次ID",
    leaderType: "负责人类型",
    refId: "负责人ID",
    leaderId: "负责人记录ID",
    volunteerIds: "志愿者ID列表",
    activityId: "活动ID",
    status: "状态",
    attendStatus: "到位状态",
    auditStatus: "审核状态",
    enrollmentStatus: "报名状态",
    recordStatus: "记录状态",
    checkInTime: "签到时间",
    type: "违规类型",
    remark: "备注",
    content: "活动总结",
    imageUrls: "图片地址",
    points: "积分",
    changeType: "变更类型",
    newValue: "新值",
    reason: "原因",
    phone: "手机号",
    name: "姓名",
    idCard: "身份证号",
    flag: "管理团队标记",
    username: "账号",
    realName: "姓名",
    password: "密码",
    newPassword: "新密码",
    department: "部门",
    keyword: "关键字",
    page: "页码",
    size: "每页数量",
    title: "标题",
    fileUrl: "文件地址",
    downloadable: "是否开放下载",
    sort: "排序值",
    permissionIds: "权限"
  };
  return labels[key] || key;
}

function friendlyInput(name, label, value) {
  if (name.endsWith("leaderType")) {
    return selectField(name, label, [["1", "志愿者负责人"], ["2", "管理团队负责人"]], value || "1");
  }
  if (name.endsWith("attendStatus")) {
    return selectField(name, label, [["1", "正常/到位"], ["2", "请假"], ["3", "迟到"], ["4", "缺席"]], value || "1");
  }
  if (name.endsWith("auditStatus")) {
    return selectField(name, label, [["", "全部"], ["0", "待审核"], ["1", "已通过"], ["2", "已拒绝"]], value ?? "");
  }
  if (name.endsWith("enrollmentStatus")) {
    return selectField(name, label, [["", "全部"], ["0", "待审核"], ["1", "已通过"], ["2", "已拒绝"]], value ?? "");
  }
  if (name.endsWith("recordStatus")) {
    return selectField(name, label, [["", "全部"], ["0", "待确认"], ["1", "已确认"], ["2", "已发放积分"]], value ?? "");
  }
  if (name.startsWith("query_") && name.endsWith("status")) {
    return selectField(name, label, [["", "全部"], ["0", "待处理"], ["1", "已通过/正常"], ["2", "已拒绝/异常"]], value ?? "");
  }
  if (name.endsWith("status")) {
    return selectField(name, label, [["1", "正常/恢复"], ["0", "暂停/停用"]], value ?? "1");
  }
  if (name.endsWith("type")) {
    return selectField(name, label, [["1", "玩手机"], ["2", "服装"], ["3", "早退"], ["4", "交头接耳"]], value || "1");
  }
  if (name.endsWith("changeType")) {
    return selectField(name, label, [["1", "改签到时间"], ["2", "改签退时间"], ["3", "改积分"]], value || "3");
  }
  if (name.endsWith("flag")) {
    return selectField(name, label, [["1", "设为管理团队"], ["0", "取消管理团队"]], value ?? "1");
  }
  if (name.endsWith("downloadable")) {
    return selectField(name, label, [["true", "开放下载"], ["false", "关闭下载"]], String(value ?? true));
  }
  if (/Time$/.test(name)) {
    return field(name, label, String(value || "").slice(0, 16), "type=\"datetime-local\"");
  }
  if (["content", "remark", "reason"].some((key) => name.endsWith(key))) {
    return textareaField(name, label, value || "");
  }
  if (Array.isArray(value)) {
    return field(name, `${label}（多个用逗号分隔）`, value.join(","), "");
  }
  if (typeof value === "number") {
    return field(name, label, value, "type=\"number\"");
  }
  return field(name, label, value ?? "");
}

function openModal(title, formHtml, submitAction) {
  modalRoot.innerHTML = `
    <div class="modal-backdrop" data-action="close-modal">
      <div class="modal" role="dialog" aria-modal="true">
        <div class="modal-head">
          <h2>${title}</h2>
          <button class="icon-button" data-action="close-modal">×</button>
        </div>
        <form class="modal-form" data-submit="${submitAction}">
          <div class="form-grid">${formHtml}</div>
          <div class="modal-actions">
            <button type="button" class="button" data-action="close-modal">取消</button>
            <button type="submit" class="button primary">提交</button>
          </div>
        </form>
      </div>
    </div>
  `;
}

function closeModal() {
  modalRoot.innerHTML = "";
}

function normalizePermissionId(row) {
  return String(valueOf(row, ["id", "permissionId"], row));
}

function permissionName(row) {
  const name = valueOf(row, ["name", "permissionName", "title"], "");
  const code = valueOf(row, ["code", "permissionCode"], "");
  return name && code ? `${name}（${code}）` : name || code || normalizePermissionId(row);
}

function permissionIdsOf(result) {
  const payload = payloadOf(result);
  if (Array.isArray(payload)) return payload.map(normalizePermissionId);
  if (Array.isArray(payload?.permissionIds)) return payload.permissionIds.map(String);
  if (Array.isArray(payload?.permissions)) return payload.permissions.map(normalizePermissionId);
  if (Array.isArray(payload?.permissionCodes)) return payload.permissionCodes.map(String);
  return recordsOf(result).map(normalizePermissionId);
}

async function openVolunteerPermissionModal(id) {
  const volunteer = findRow("volunteers", id) || {};
  let options = state.data.volunteerGrantablePermissions || mockRows.volunteerGrantablePermissions;
  let selectedIds = (valueOf(volunteer, ["permissionIds"], []) || []).map(String);
  if (state.apiMode) {
    try {
      const [grantable, current] = await Promise.all([
        requestAdmin(api.admin.volunteerGrantablePermissions),
        requestAdmin(api.admin.volunteerPermissions(id))
      ]);
      options = recordsOf(grantable);
      selectedIds = permissionIdsOf(current);
    } catch (error) {
      showToast(`权限信息加载失败：${error.message}`, "error");
    }
  }
  const managerFlag = valueOf(volunteer, ["managerFlag", "manager_flag"], valueOf(volunteer, ["roleName"], "") === "管理团队" ? 1 : 0);
  openModal("管理团队/权限", `
    ${hiddenField("volunteerId", id)}
    ${field("displayName", "志愿者", valueOf(volunteer, ["realName", "name"], id), "disabled")}
    ${selectField("managerFlag", "管理团队标记", [["1", "设为管理团队"], ["0", "取消管理团队"]], managerFlag)}
    <div class="field field-full">
      <span>可授权权限</span>
      <div class="permission-checks">
        ${options.length ? options.map((item) => {
          const permissionId = normalizePermissionId(item);
          const permissionCode = valueOf(item, ["code", "permissionCode"], "");
          const checked = selectedIds.includes(permissionId) || selectedIds.includes(permissionCode);
          return `
            <label class="check-row permission-check">
              <input type="checkbox" name="permissionIds" value="${escapeHtml(permissionId)}" ${checked ? "checked" : ""} />
              <span>${escapeHtml(permissionName(item))}</span>
            </label>
          `;
        }).join("") : `<div class="muted">暂无可授权权限</div>`}
      </div>
    </div>
  `, "submit-volunteer-permissions");
}

function openActivityModal(mode = "normal", existing = null) {
  const title = mode === "historical" ? "发布历史活动" : mode === "edit" ? "编辑活动" : "发布活动";
  const id = existing ? valueOf(existing, ["id", "activityId"], "") : "";
  const startTime = String(valueOf(existing, ["startTime", "activityStartTime", "beginTime"], "2026-06-10T09:00")).slice(0, 16);
  const endTime = String(valueOf(existing, ["endTime", "activityEndTime", "finishTime"], "2026-06-10T12:00")).slice(0, 16);
  const enrollDeadline = String(valueOf(existing, ["enrollDeadline", "signupDeadline"], "2026-06-09T18:00")).slice(0, 16);
  const cancelDeadline = String(valueOf(existing, ["cancelDeadline"], valueOf(existing, ["enrollDeadline", "signupDeadline"], "2026-06-09T18:00"))).slice(0, 16);
  const enrollOpenManager = String(valueOf(existing, ["enrollOpenManager"], startTime)).slice(0, 16);
  const enrollOpenLeader = String(valueOf(existing, ["enrollOpenLeader"], startTime)).slice(0, 16);
  const enrollOpenVolunteer = String(valueOf(existing, ["enrollOpenVolunteer", "signupStartTime", "enrollStartTime"], startTime)).slice(0, 16);
  openModal(title, `
    ${field("title", "活动名称", valueOf(existing, ["title", "name", "activityName"], "雷城客运站志愿服务活动"), "required")}
    ${field("coverImageUrl", "封面图 URL", valueOf(existing, ["coverImageUrl", "imageUrl"], ""))}
    ${field("location", "活动地点", valueOf(existing, ["location", "place", "address"], "雷州市西湖街道西湖新村17号"), "required")}
    ${field("lat", "GPS 纬度", valueOf(existing, ["lat", "latitude"], ""))}
    ${field("lng", "GPS 经度", valueOf(existing, ["lng", "longitude"], ""))}
    ${field("startTime", "活动开始时间", startTime, "type=\"datetime-local\" required")}
    ${field("endTime", "活动结束时间", endTime, "type=\"datetime-local\" required")}
    ${field("enrollDeadline", "报名截止时间", enrollDeadline, "type=\"datetime-local\"")}
    ${field("cancelDeadline", "取消报名截止时间", cancelDeadline, "type=\"datetime-local\"")}
    ${field("enrollOpenManager", "管理团队开放报名", enrollOpenManager, "type=\"datetime-local\"")}
    ${field("enrollOpenLeader", "临时负责人开放报名", enrollOpenLeader, "type=\"datetime-local\"")}
    ${field("enrollOpenVolunteer", "志愿者开放报名", enrollOpenVolunteer, "type=\"datetime-local\"")}
    ${field("pointsBase", "基础积分", valueOf(existing, ["pointsBase", "points"], "10"), "type=\"number\" min=\"0\"")}
    ${field("managerMultiplier", "管理团队积分倍率", valueOf(existing, ["managerMultiplier"], "1.2"), "type=\"number\" step=\"0.1\" min=\"0\"")}
    ${field("leaderMultiplier", "临时负责人积分倍率", valueOf(existing, ["leaderMultiplier"], "1.1"), "type=\"number\" step=\"0.1\" min=\"0\"")}
    ${selectField("needAudit", "报名需审核", [["0", "否"], ["1", "是"]], valueOf(existing, ["needAudit"], "0"))}
    ${selectField("enrollScope", "报名限制", [["0", "全平台"], ["1", "指定分队"]], valueOf(existing, ["enrollScope"], "0"))}
    ${field("requireMinJoinCount", "已参加次数门槛", valueOf(existing, ["requireMinJoinCount"], "0"), "type=\"number\" min=\"0\"")}
    ${field("requireMinJoinMinutes", "已服务时长门槛(分钟)", valueOf(existing, ["requireMinJoinMinutes"], "0"), "type=\"number\" min=\"0\"")}
    ${field("checkInRadiusM", "签到半径(米)", valueOf(existing, ["checkInRadiusM"], "500"), "type=\"number\" min=\"0\"")}
    ${field("contactName", "联系人", valueOf(existing, ["contactName"], "邝大程"))}
    ${field("contactPhone", "联系电话", valueOf(existing, ["contactPhone"], "15766508094"))}
    ${field("publisherDeptName", "发布部门", valueOf(existing, ["publisherDeptName"], "组织部"))}
    ${field("slotProjectName", "岗位名称", valueOf(existing, ["slotProjectName"], "志愿者"))}
    ${field("slotNeedCount", "岗位人数", valueOf(existing, ["quota", "needCount", "demandCount", "capacity"], "20"), "type=\"number\"")}
    ${textareaField("content", "活动内容", valueOf(existing, ["content", "activityContent"], "请按岗位时间到达现场，服从负责人安排。"))}
    ${textareaField("requirement", "报名要求", valueOf(existing, ["requirement"], "完成实名注册即可报名。"))}
    ${textareaField("enrollNotice", "报名须知", valueOf(existing, ["enrollNotice"], "报名成功后请准时参加。"))}
    ${helpText("图片上传/裁剪、服务保障图标、报名成功提示图暂不在本静态后台内上传；当前用 URL 和基础字段联调 jar。GPS 经纬度必须同时填写或同时留空。")}
  `, mode === "historical" ? "submit-historical" : mode === "edit" ? `submit-activity-edit:${id}` : "submit-activity");
}

function openRecurringModal() {
  openModal("周期发布活动", `
    ${field("title", "活动名称", "固定日期志愿服务活动", "required")}
    ${field("location", "活动地点", "雷州市西湖街道西湖新村17号", "required")}
    ${field("startTime", "模板开始时间", "2026-06-10T09:00", "type=\"datetime-local\" required")}
    ${field("endTime", "模板结束时间", "2026-06-10T12:00", "type=\"datetime-local\" required")}
    ${field("recurStart", "周期开始日期", "2026-06-10", "type=\"date\"")}
    ${field("recurEnd", "周期结束日期", "2026-06-30", "type=\"date\"")}
    ${field("weekdays", "星期几，逗号分隔", "6,7")}
    ${field("slotProjectName", "岗位名称", "志愿者")}
    ${field("slotNeedCount", "岗位人数", "20", "type=\"number\"")}
    ${textareaField("content", "活动内容", "周期活动模板内容。")}
    ${textareaField("dates", "显式日期，可空", "")}
  `, "submit-recurring");
}

function openBannerModal(existing = null) {
  const id = existing ? valueOf(existing, ["id", "bannerId"], "") : "";
  openModal(existing ? "编辑轮播图" : "新增轮播图", `
    ${field("title", "标题", valueOf(existing, ["title"], "雷州公益同行"), "required")}
    ${field("imageUrl", "图片 URL", valueOf(existing, ["imageUrl", "coverImageUrl"], "https://example.com/banner.jpg"), "required")}
    ${selectField("linkType", "跳转类型", [["0", "不跳转"], ["1", "公众号推文"], ["2", "小程序页面"]], valueOf(existing, ["linkType"], "0"))}
    ${field("linkUrl", "跳转地址", valueOf(existing, ["linkUrl"], "/pages/activity/list"))}
    ${field("sort", "排序", valueOf(existing, ["sort"], "1"), "type=\"number\"")}
    ${selectField("status", "状态", [["1", "启用"], ["0", "下架"]], valueOf(existing, ["status"], "1"))}
    ${helpText("公众号推文在小程序内会复制链接；小程序页面请填写 /pages/... 路径。")}
  `, existing ? `submit-banner-edit:${id}` : "submit-banner");
}

function openAnnouncementModal(existing = null) {
  const id = existing ? valueOf(existing, ["id", "announcementId"], "") : "";
  openModal(existing ? "编辑公告" : "新增公告", `
    ${field("title", "标题", valueOf(existing, ["title"], "公告栏静态测试图片"), "required")}
    ${field("summary", "摘要", valueOf(existing, ["summary"], "点击图片跳转推文或小程序"))}
    ${field("coverImageUrl", "公告图片 URL", valueOf(existing, ["coverImageUrl", "imageUrl"], "https://example.com/notice.jpg"))}
    ${selectField("linkType", "跳转类型", [["0", "不跳转"], ["1", "公众号推文"], ["2", "小程序页面"]], valueOf(existing, ["linkType"], "0"))}
    ${field("linkUrl", "跳转地址", valueOf(existing, ["linkUrl"], "https://mp.weixin.qq.com/"))}
    ${selectField("status", "状态", [["1", "启用"], ["0", "草稿"]], valueOf(existing, ["status"], "1"))}
    ${textareaField("content", "正文", valueOf(existing, ["content"], "公告正文内容。"))}
    ${helpText("首页公告栏只取一张公告图片展示；点击图片按这里的跳转类型处理。")}
  `, existing ? `submit-announcement-edit:${id}` : "submit-announcement");
}

function openSquadModal(existing = null) {
  const id = existing ? valueOf(existing, ["id", "squadId"], "") : "";
  openModal("创建分队", `
    ${field("name", "分队名称", valueOf(existing, ["name", "squadName"], "雷州市第一中学分队"), "required")}
    ${field("type", "分队类型", valueOf(existing, ["type", "squadType"], "中学分队"), "required")}
    ${field("leaderName", "负责人", valueOf(existing, ["leaderName", "leader"], "邝大程"))}
    ${field("leaderPhone", "联系方式", valueOf(existing, ["leaderPhone", "phone"], "15766508094"))}
    ${field("memberLimit", "人数上限", valueOf(existing, ["memberLimit"], "500"), "type=\"number\"")}
    ${field("visibleFields", "同队可见字段", valueOf(existing, ["visibleFields"], "姓名/学校/手机号"))}
    ${selectField("status", "状态", [["1", "启用"], ["0", "停用"]])}
  `, existing ? `submit-squad-edit:${id}` : "submit-squad");
}

function openGroupTransferModal(id) {
  openModal("组长转移", `
    ${field("volunteerId", "新组长志愿者 ID", "", "required type=\"number\"")}
    ${textareaField("reason", "转移原因", "后台组长转移。")}
  `, `submit-group-transfer:${id}`);
}

function openApiCallModal(target) {
  const params = JSON.parse(target.dataset.params || "{}");
  const body = JSON.parse(target.dataset.body || "{}");
  const query = JSON.parse(target.dataset.query || "{}");
  const paramFields = Object.keys(params).map((key) => {
    return friendlyInput(`param_${key}`, params[key], "");
  }).join("");
  const queryFields = Object.keys(query).map((key) => {
    return friendlyInput(`query_${key}`, friendlyLabel(key), query[key]);
  }).join("");
  const bodyFields = Object.keys(body).map((key) => {
    return friendlyInput(`body_${key}`, friendlyLabel(key), body[key]);
  }).join("");
  const hasFields = Boolean(paramFields || queryFields || bodyFields);
  openModal(target.dataset.label, `
    ${hiddenField("_label", target.dataset.label || "接口调用")}
    ${hiddenField("method", target.dataset.method)}
    ${hiddenField("path", target.dataset.path)}
    ${hiddenField("_bodySample", JSON.stringify(body))}
    ${hiddenField("_querySample", JSON.stringify(query))}
    ${paramFields}
    ${queryFields}
    ${bodyFields}
    ${hasFields ? "" : helpText("这个功能不需要填写信息，直接点击提交即可。")}
  `, "submit-api-call");
}

function parseFriendlyValue(raw, sample) {
  const value = typeof raw === "string" ? raw.trim() : raw;
  if (Array.isArray(sample)) {
    if (value === "") return [];
    return String(value).split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean).map((item) => {
      const number = Number(item);
      return Number.isNaN(number) ? item : number;
    });
  }
  if (typeof sample === "number") {
    if (value === "") return "";
    const number = Number(value);
    return Number.isNaN(number) ? 0 : number;
  }
  if (typeof sample === "boolean") {
    return value === true || String(value) === "true" || String(value) === "1";
  }
  return value;
}

function buildFriendlyBody(values, sampleBody) {
  return Object.keys(sampleBody).reduce((acc, key) => {
    acc[key] = parseFriendlyValue(values[`body_${key}`], sampleBody[key]);
    return acc;
  }, {});
}

function appendFriendlyQuery(path, values, sampleQuery) {
  const params = new URLSearchParams();
  Object.keys(sampleQuery).forEach((key) => {
    const raw = values[`query_${key}`];
    if (isEmptyValue(raw)) return;
    const value = parseFriendlyValue(raw, sampleQuery[key]);
    if (value === "" || value === undefined || value === null) return;
    if (Array.isArray(value)) {
      value.forEach((item) => params.append(key, item));
      return;
    }
    params.set(key, value);
  });
  const queryText = params.toString();
  if (!queryText) return path;
  return `${path}${path.includes("?") ? "&" : "?"}${queryText}`;
}

function isEmptyValue(value) {
  return value === "" || value === undefined || value === null || (Array.isArray(value) && !value.length);
}

function validateApiBusinessInput(label, body) {
  const requiredMap = {
    手动新增报名: ["volunteerId", "slotIds"],
    报名审核拒绝: ["reason"],
    指派活动负责人: ["leaderType", "refId"],
    "标记到位/确认签到": ["attendStatus"],
    记录违规: ["type", "remark"],
    上传活动总结: ["content"],
    发放积分: ["points"],
    "申请改考勤/积分": ["changeType", "newValue", "reason"],
    活动补录申请: ["slotId", "reason"],
    批准建组: ["reason"],
    拒绝建组: ["reason"],
    批准加入分队: ["reason"],
    拒绝加入分队: ["reason"],
    设置管理团队标记: ["flag"],
    创建子账号: ["username", "password", "realName", "phone", "department"],
    修改子账号: ["realName", "phone", "department"],
    替换权限集合: ["permissionIds"],
    重置子账号密码: ["newPassword"],
    新增文件下载: ["title", "fileUrl"],
    "开放/关闭下载": ["downloadable"],
    调整轮播排序: ["sort"],
    "暂停/恢复志愿者": ["status"]
  };
  const required = requiredMap[label] || [];
  const missing = required.filter((key) => isEmptyValue(body[key]));
  if (missing.length) {
    throw new Error(`${missing.map(friendlyLabel).join("、")}不能为空`);
  }
  if (label === "活动补录申请" && isEmptyValue(body.idCard) && isEmptyValue(body.phone)) {
    throw new Error("身份证号和手机号至少填写一项");
  }
}

function openBackfillModal() {
  openModal("活动补录", `
    ${field("activityId", "活动 ID", "", "required")}
    ${field("slotId", "时间段 ID", "", "required")}
    ${field("name", "志愿者姓名", "")}
    ${field("phone", "手机号", "")}
    ${field("idCard", "身份证号", "")}
    ${textareaField("reason", "补录原因", "历史数据补录。")}
  `, "submit-backfill");
}

function formValues(form) {
  const data = new FormData(form);
  const values = {};
  data.forEach((value, key) => {
    const normalized = typeof value === "string" ? value.trim() : value;
    if (values[key] !== undefined) {
      values[key] = Array.isArray(values[key]) ? values[key].concat(normalized) : [values[key], normalized];
      return;
    }
    values[key] = normalized;
  });
  return values;
}

function buildActivityPayload(values) {
  const startTime = normalizeDateTime(values.startTime);
  const endTime = normalizeDateTime(values.endTime);
  const lat = numberOrNull(values.lat);
  const lng = numberOrNull(values.lng);
  return {
    title: values.title,
    coverImageUrl: values.coverImageUrl || null,
    location: values.location,
    lat,
    lng,
    content: values.content || "",
    requirement: values.requirement || "",
    startTime,
    endTime,
    enrollDeadline: normalizeDateTime(values.enrollDeadline) || startTime,
    cancelDeadline: normalizeDateTime(values.cancelDeadline) || normalizeDateTime(values.enrollDeadline) || startTime,
    pointsBase: numberOrNull(values.pointsBase) ?? 0,
    leaderMultiplier: numberOrNull(values.leaderMultiplier) ?? 1,
    managerMultiplier: numberOrNull(values.managerMultiplier) ?? 1,
    needAudit: numberOrNull(values.needAudit) ?? 0,
    enrollScope: numberOrNull(values.enrollScope) ?? 0,
    requireMinJoinCount: numberOrNull(values.requireMinJoinCount) ?? 0,
    requireMinJoinMinutes: numberOrNull(values.requireMinJoinMinutes) ?? 0,
    minProjects: 1,
    maxProjects: 1,
    enrollNotice: values.enrollNotice || "",
    contactName: values.contactName || "",
    contactPhone: values.contactPhone || "",
    publisherDeptName: values.publisherDeptName || "组织部",
    enrollOpenManager: normalizeDateTime(values.enrollOpenManager) || startTime,
    enrollOpenLeader: normalizeDateTime(values.enrollOpenLeader) || startTime,
    enrollOpenVolunteer: normalizeDateTime(values.enrollOpenVolunteer) || startTime,
    checkInRadiusM: numberOrNull(values.checkInRadiusM) ?? 500,
    slots: [{
      projectName: values.slotProjectName || "志愿者",
      startTime,
      endTime,
      needCount: numberOrNull(values.slotNeedCount) || 1
    }]
  };
}

async function submitRealOrMock(kind, path, body, method = "POST") {
  if (state.apiMode) {
    if (!state.token) throw new Error("请先登录管理端");
    const result = await requestAdmin(path, { method, body });
    showToast("提交成功", "success");
    closeModal();
    await loadRemoteView(state.active);
    return result;
  }
  showToast("Mock 模式已模拟提交", "success");
  closeModal();
  const collectionMap = {
    activity: "activities",
    banner: "banners",
    announcement: "announcements",
    squad: "squads",
    group: "groups"
  };
  const collection = collectionMap[kind];
  if (collection && method === "PUT") {
    const id = String(path.split("/").filter(Boolean).pop());
    state.data[collection] = (state.data[collection] || []).map((row) => (
      String(valueOf(row, ["id", "activityId", "groupId", "squadId"], "")) === id ? Object.assign({}, row, body) : row
    ));
  } else {
    if (kind === "activity") state.data.activities.unshift(Object.assign({ id: Date.now(), status: 1 }, body));
    if (kind === "banner") state.data.banners.unshift(Object.assign({ id: Date.now() }, body));
    if (kind === "announcement") state.data.announcements.unshift(Object.assign({ id: Date.now() }, body));
    if (kind === "squad") state.data.squads.unshift(Object.assign({ id: Date.now(), memberCount: 0 }, body));
  }
  render();
  return { code: 0 };
}

async function handleFormSubmit(form) {
  const values = formValues(form);
  const action = form.dataset.submit;
  const [type, id] = action.split(":");
  try {
    if (type === "submit-activity") {
      await submitRealOrMock("activity", api.admin.activities, buildActivityPayload(values));
    } else if (type === "submit-activity-edit") {
      await submitRealOrMock("activity", api.admin.activityDetail(id), buildActivityPayload(values), "PUT");
    } else if (type === "submit-historical") {
      await submitRealOrMock("activity", api.admin.activityHistorical, buildActivityPayload(values));
    } else if (type === "submit-recurring") {
      const payload = {
        template: buildActivityPayload(values),
        dates: splitList(values.dates),
        recurStart: values.recurStart || null,
        recurEnd: values.recurEnd || null,
        weekdays: splitList(values.weekdays, Number)
      };
      await submitRealOrMock("activity", api.admin.activityRecurring, payload);
    } else if (type === "submit-banner" || type === "submit-banner-edit") {
      await submitRealOrMock("banner", type === "submit-banner-edit" ? api.admin.bannerDetail(id) : api.admin.banners, {
        title: values.title,
        imageUrl: values.imageUrl,
        linkType: numberOrNull(values.linkType) || 0,
        linkUrl: values.linkUrl || "",
        sort: numberOrNull(values.sort) || 0,
        status: numberOrNull(values.status) ?? 1
      }, type === "submit-banner-edit" ? "PUT" : "POST");
    } else if (type === "submit-announcement" || type === "submit-announcement-edit") {
      await submitRealOrMock("announcement", type === "submit-announcement-edit" ? api.admin.announcementDetail(id) : api.admin.announcements, {
        title: values.title,
        summary: values.summary || "",
        content: values.content || "",
        coverImageUrl: values.coverImageUrl || "",
        linkType: numberOrNull(values.linkType) || 0,
        linkUrl: values.linkUrl || "",
        status: numberOrNull(values.status) ?? 1
      }, type === "submit-announcement-edit" ? "PUT" : "POST");
    } else if (type === "submit-squad" || type === "submit-squad-edit") {
      await submitRealOrMock("squad", type === "submit-squad-edit" ? api.admin.squadDetail(id) : api.admin.squads, {
        name: values.name,
        type: values.type,
        leaderName: values.leaderName || "",
        leaderPhone: values.leaderPhone || "",
        memberLimit: numberOrNull(values.memberLimit) || 0,
        visibleFields: values.visibleFields || "",
        status: numberOrNull(values.status) ?? 1
      }, type === "submit-squad-edit" ? "PUT" : "POST");
    } else if (type === "submit-group-transfer") {
      await submitRealOrMock("group", api.admin.groupLeader(id), {
        volunteerId: numberOrNull(values.volunteerId),
        reason: values.reason || ""
      }, "PUT");
    } else if (type === "submit-volunteer-permissions") {
      const volunteerId = values.volunteerId;
      const permissionIds = Array.isArray(values.permissionIds)
        ? values.permissionIds.map(Number).filter((item) => !Number.isNaN(item))
        : values.permissionIds ? [Number(values.permissionIds)].filter((item) => !Number.isNaN(item)) : [];
      const managerFlag = numberOrNull(values.managerFlag) ?? 0;
      if (!volunteerId) throw new Error("志愿者ID不能为空");
      if (state.apiMode) {
        await requestAdmin(api.admin.volunteerManagerFlag(volunteerId), {
          method: "PUT",
          body: { flag: managerFlag }
        });
        await requestAdmin(api.admin.volunteerPermissions(volunteerId), {
          method: "PUT",
          body: { permissionIds: managerFlag ? permissionIds : [] }
        });
        showToast("权限已保存", "success");
        closeModal();
        await loadRemoteView("volunteers");
      } else {
        state.data.volunteers = state.data.volunteers.map((row) => {
          if (String(valueOf(row, ["id", "volunteerId"], "")) !== String(volunteerId)) return row;
          return Object.assign({}, row, {
            managerFlag,
            roleName: managerFlag ? "管理团队" : "志愿者",
            permissionIds: managerFlag ? permissionIds : []
          });
        });
        showToast("Mock 模式已保存权限", "success");
        closeModal();
        render();
      }
    } else if (type === "submit-api-call") {
      const label = values._label || "接口调用";
      const params = Object.keys(values)
        .filter((key) => key.startsWith("param_"))
        .reduce((acc, key) => {
          const paramKey = key.replace("param_", "");
          if (isEmptyValue(values[key])) {
            throw new Error(`${params[paramKey] || friendlyLabel(paramKey)}不能为空`);
          }
          acc[paramKey] = values[key];
          return acc;
        }, {});
      let path = values.path;
      Object.keys(params).forEach((key) => {
        path = path.replace(`{${key}}`, encodeURIComponent(params[key]));
      });
      if (/\{[^}]+\}/.test(path)) {
        throw new Error("请填写所有路径参数");
      }
      const sampleQuery = JSON.parse(values._querySample || "{}");
      path = appendFriendlyQuery(path, values, sampleQuery);
      let body = undefined;
      const sampleBody = JSON.parse(values._bodySample || "{}");
      if (Object.keys(sampleBody).length && !["GET"].includes(values.method)) {
        body = buildFriendlyBody(values, sampleBody);
        validateApiBusinessInput(label, body);
      }
      let result = {
        mock: true,
        message: "Mock 模式已模拟调用",
        request: { method: values.method, path, body: body || null }
      };
      if (state.apiMode) {
        result = values.method === "GET" && path.includes("export")
          ? await downloadAdmin(path, { method: values.method, body })
          : await requestAdmin(path, { method: values.method, body });
      }
      state.apiResult = {
        ok: true,
        label,
        method: values.method,
        path,
        result
      };
      showToast(state.apiMode ? "接口调用成功" : "Mock 模式已模拟调用", "success");
      closeModal();
      if (state.apiMode) await loadRemoteView(state.active);
      else render();
    } else if (type === "submit-backfill") {
      await submitRealOrMock("backfill", api.admin.backfillCreate(values.activityId), {
        idCard: values.idCard || null,
        phone: values.phone || null,
        name: values.name || null,
        slotId: numberOrNull(values.slotId),
        reason: values.reason || ""
      });
    }
  } catch (error) {
    state.apiResult = {
      ok: false,
      label: values._label || "接口调用",
      method: values.method || "",
      path: values.path || "",
      result: { message: error.message }
    };
    render();
    showToast(`提交失败：${error.message}`, "error");
  }
}

async function deleteRealOrMock(collection, path, label, body) {
  if (!window.confirm(`确认${label}？此操作会影响小程序展示数据。`)) return;
  if (state.apiMode) {
    if (!state.token) throw new Error("请先登录管理端");
    await requestAdmin(path, { method: "DELETE", body });
  } else {
    state.data[collection] = (state.data[collection] || []).filter((row) => String(valueOf(row, ["id", "activityId", "groupId", "squadId"], "")) !== String(body?.id));
  }
  showToast(`${label}成功`, "success");
  if (state.apiMode) {
    await loadRemoteView(state.active);
  } else {
    render();
  }
}

async function handleAction(action, target) {
  if (action === "close-modal") return closeModal();
  if (action === "clear-api-result") {
    state.apiResult = null;
    return render();
  }
  if (action === "refresh") return loadRemoteView(state.active);
  if (action === "open-activity-modal") return openActivityModal();
  if (action === "open-historical-modal") return openActivityModal("historical");
  if (action === "open-recurring-modal") return openRecurringModal();
  if (action === "open-banner-modal") return openBannerModal();
  if (action === "open-announcement-modal") return openAnnouncementModal();
  if (action === "open-squad-modal") return openSquadModal();
  if (action === "open-backfill-modal") return openBackfillModal();
  if (action === "open-api-call") return openApiCallModal(target);

  const id = target.dataset.id;
  try {
    if (action === "edit-activity") return openActivityModal("edit", findRow("activities", id));
    if (action === "edit-banner") return openBannerModal(findRow("banners", id));
    if (action === "edit-announcement") return openAnnouncementModal(findRow("announcements", id));
    if (action === "edit-squad") return openSquadModal(findRow("squads", id));
    if (action === "transfer-group") return openGroupTransferModal(id);
    if (action === "open-volunteer-permissions") return openVolunteerPermissionModal(id);
    if (action === "delete-activity") return deleteRealOrMock("activities", api.admin.activityDetail(id), "删除活动", { id });
    if (action === "delete-banner") return deleteRealOrMock("banners", api.admin.bannerDetail(id), "删除轮播图", { id });
    if (action === "delete-announcement") return deleteRealOrMock("announcements", api.admin.announcementDetail(id), "删除公告", { id });
    if (action === "delete-squad") return deleteRealOrMock("squads", api.admin.squadDetail(id), "删除分队", { id });
    if (action === "delete-group") return deleteRealOrMock("groups", api.admin.groups + `/${id}`, "解散小组", { id, reason: "后台解散小组" });

    if (!state.apiMode) {
      showToast("Mock 模式已模拟操作", "success");
      return;
    }
    if (action === "copy-activity") {
      await requestAdmin(api.admin.activityCopy(id), { method: "POST" });
      showToast("复制成功", "success");
      await loadRemoteView("activities");
    } else if (action === "load-enrollments") {
      const result = await requestAdmin(api.admin.activityEnrollments(id));
      showToast(`报名记录 ${recordsOf(result).length} 条`, "success");
    } else if (action === "approve-attendance-change") {
      await requestAdmin(api.admin.attendanceChangeApprove(id), { method: "POST", body: { reason: "同意" } });
      await loadRemoteView("operations");
    } else if (action === "reject-attendance-change") {
      await requestAdmin(api.admin.attendanceChangeReject(id), { method: "POST", body: { reason: "拒绝" } });
      await loadRemoteView("operations");
    } else if (action === "approve-backfill") {
      await requestAdmin(api.admin.backfillApprove(id), { method: "POST", body: { reason: "同意" } });
      await loadRemoteView("operations");
    } else if (action === "reject-backfill") {
      await requestAdmin(api.admin.backfillReject(id), { method: "POST", body: { reason: "拒绝" } });
      await loadRemoteView("operations");
    }
  } catch (error) {
    showToast(`操作失败：${error.message}`, "error");
  }
}

nav.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-key]");
  if (!button) return;
  state.active = button.dataset.key;
  render();
  if (state.apiMode) await loadRemoteView(state.active);
});

content.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (!target) return;
  handleAction(target.dataset.action, target);
});

modalRoot.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (target && target.classList.contains("modal-backdrop") && event.target !== target) {
    return;
  }
  if (target && target.classList.contains("modal-backdrop")) {
    return;
  }
  if (target) handleAction(target.dataset.action, target);
});

modalRoot.addEventListener("submit", (event) => {
  event.preventDefault();
  handleFormSubmit(event.target);
});

loginButton.addEventListener("click", loginAdmin);
logoutButton.addEventListener("click", logoutAdmin);
apiModeButton.addEventListener("click", async () => {
  state.apiMode = !state.apiMode;
  localStorage.setItem("hengdeAdminUseRealApi", String(state.apiMode));
  await loadRemoteView(state.active);
});

document.querySelector(".icon-button[title='刷新']").addEventListener("click", () => loadRemoteView(state.active));

render();
if (state.apiMode && state.token) {
  loadRemoteView(state.active);
}
