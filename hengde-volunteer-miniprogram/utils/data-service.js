const mock = require("./mock");
const auth = require("./auth");
const { useMockApi } = require("./api-mode");
const { request } = require("./request");
const { ENDPOINTS } = require("./api-endpoints");

function payloadOf(response) {
  if (!response || typeof response !== "object") {
    return response;
  }
  if (response.data !== undefined) {
    return response.data;
  }
  return response;
}

function recordsOf(response) {
  const payload = payloadOf(response);
  if (Array.isArray(payload)) {
    return payload;
  }
  if (!payload || typeof payload !== "object") {
    return [];
  }
  if (Array.isArray(payload.records)) return payload.records;
  if (Array.isArray(payload.list)) return payload.list;
  if (Array.isArray(payload.items)) return payload.items;
  if (Array.isArray(payload.content)) return payload.content;
  if (Array.isArray(payload.rows)) return payload.rows;
  if (payload.data !== undefined && payload.data !== payload) return recordsOf(payload);
  return [];
}

function recordsOrSingle(response) {
  const records = recordsOf(response);
  if (records.length) return records;
  const payload = payloadOf(response);
  if (payload && typeof payload === "object" && !Array.isArray(payload)) {
    return [payload];
  }
  return [];
}

function valueOf(row, keys, fallback = "") {
  for (const key of keys) {
    if (row && row[key] !== undefined && row[key] !== null && row[key] !== "") {
      return row[key];
    }
  }
  return fallback;
}

function datePart(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 10);
}

function minutePart(value) {
  if (!value) return "";
  const text = String(value).replace("T", " ");
  return text.length >= 16 ? text.slice(11, 16) : text;
}

function listOf(value) {
  if (Array.isArray(value)) return value;
  if (typeof value === "string") {
    return value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean);
  }
  return recordsOf(value);
}

function minutesText(value) {
  const minutes = Number(value || 0);
  if (!minutes) return "0分钟";
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (!hours) return `${rest}分钟`;
  if (!rest) return `${hours}小时`;
  return `${hours}小时${rest}分钟`;
}

function statusText(value, map, fallback = "未开放") {
  if (value === undefined || value === null || value === "") {
    return fallback;
  }
  return map[value] || map[String(value)] || String(value);
}

function normalizeActivity(row) {
  const start = valueOf(row, ["startTime", "activityStartTime", "beginTime", "startAt"]);
  const end = valueOf(row, ["endTime", "activityEndTime", "finishTime", "endAt"]);
  const coverImages = listOf(valueOf(row, ["coverImages", "images", "imageUrls", "carouselImages"], []));
  const rawSlots = recordsOf(valueOf(row, ["slots", "projects", "items"], []));
  const rawTimeSlots = recordsOf(valueOf(row, ["timeSlots", "activityTimeSlots", "periods"], rawSlots));
  const pointsBase = Number(valueOf(row, ["points", "basePoints", "pointsBase", "score"], 0)) || 0;
  const managerMultiplier = Number(valueOf(row, ["managerMultiplier"], 1)) || 1;
  const leaderMultiplier = Number(valueOf(row, ["leaderMultiplier"], 1)) || 1;
  const signupTimes = valueOf(row, ["signupTimes", "registrationTimes"], null) || {
    manager: valueOf(row, ["enrollOpenManager", "managerEnrollOpenAt"], ""),
    leader: valueOf(row, ["enrollOpenLeader", "leaderEnrollOpenAt"], ""),
    volunteer: valueOf(row, ["enrollOpenVolunteer", "signupStartTime", "enrollStartTime"], "")
  };
  const recruitInfo = valueOf(row, ["recruitInfo", "registrationInfo"], null) || {
    deadline: valueOf(row, ["enrollDeadline", "signupDeadline", "registrationDeadline"], ""),
    limit: statusText(valueOf(row, ["enrollScope", "targetScope", "targetScopeName", "signupTarget"]), {
      0: "全平台",
      1: "指定分队"
    }, valueOf(row, ["target", "targetScopeName", "signupTarget"], "全平台")),
    requirement: valueOf(row, ["requirement"], "")
  };
  // 优先用后端实时派生的 displayStatus（0未开放/1报名中/2报名截止/3活动中/4已结束）；
  // 后端未给（旧接口/详情）才退回持久化的 status——后者对已发布活动恒为「报名中」，不反映截止/结束。
  const status = statusText(valueOf(row, ["displayStatus", "statusName", "statusText", "status"]), {
    0: "未开放",
    1: "报名中",
    2: "报名截止",
    3: "活动中",
    4: "已结束",
    DRAFT: "未开放",
    PUBLISHED: "报名中",
    CLOSED: "报名截止",
    IN_PROGRESS: "活动中",
    ONGOING: "活动中",
    FINISHED: "已结束"
  });

  return {
    id: String(valueOf(row, ["id", "activityId"], "")),
    no: valueOf(row, ["activityNo", "no", "code"], ""),
    title: valueOf(row, ["title", "name", "activityName", "activityTitle"], "未命名活动"),
    team: valueOf(row, ["team", "publishTeamName", "publisherTeam"], ""),
    coverImageUrl: valueOf(row, ["coverImageUrl", "imageUrl", "coverUrl"], ""),
    coverImages,
    activityNo: valueOf(row, ["activityNo", "no", "code"], ""),
    date: datePart(start || valueOf(row, ["date", "activityDate"], "")),
    time: start && end ? `${minutePart(start)}-${minutePart(end)}` : valueOf(row, ["time", "timeRange"], ""),
    startTime: start,
    endTime: end,
    signupStartTime: valueOf(row, ["signupStartTime", "enrollStartTime", "registrationStartTime", "applyStartTime", "enrollOpenVolunteer"], ""),
    enrollDeadline: valueOf(row, ["enrollDeadline", "signupDeadline", "registrationDeadline"], ""),
    place: valueOf(row, ["place", "location", "address"], ""),
    latitude: valueOf(row, ["latitude", "lat"], ""),
    longitude: valueOf(row, ["longitude", "lng", "lon"], ""),
    quota: valueOf(row, ["quota", "needCount", "demandCount", "capacity", "totalCount", "recruitCount"], 0),
    joined: valueOf(row, ["joined", "enrolledCount", "enrollCount", "signupCount", "registeredCount"], 0),
    enrolledCount: valueOf(row, ["enrolledCount", "enrollCount", "joined", "signupCount", "registeredCount"], 0),
    hasQuota: valueOf(row, ["hasQuota"], null),
    points: pointsBase,
    status,
    target: valueOf(row, ["target", "targetScopeName", "signupTarget"], "全平台"),
    contact: valueOf(row, ["contact", "contactName", "contactPhone"], ""),
    contactName: valueOf(row, ["contactName", "contactPerson"], ""),
    contactPhone: valueOf(row, ["contactPhone", "phone", "mobile"], ""),
    contactOrg: valueOf(row, ["contactOrg", "organizationName", "team"], ""),
    summary: valueOf(row, ["summary", "content", "description", "requirement"], ""),
    content: valueOf(row, ["content", "activityContent", "description", "summary"], ""),
    signupTimes,
    pointsByRole: valueOf(row, ["pointsByRole", "rolePoints"], null) || {
      manager: Math.round(pointsBase * managerMultiplier * 10) / 10,
      leader: Math.round(pointsBase * leaderMultiplier * 10) / 10,
      volunteer: pointsBase
    },
    timeSlots: rawTimeSlots,
    recruitInfo,
    requireMinJoinCount: valueOf(row, ["requireMinJoinCount"], 0),
    requireMinJoinMinutes: valueOf(row, ["requireMinJoinMinutes"], 0),
    guarantees: recordsOf(valueOf(row, ["guarantees", "serviceGuarantees", "benefits"], [])),
    registrants: recordsOf(valueOf(row, ["registrants", "signupUsers", "enrolledUsers"], [])),
    slots: rawSlots,
    leaderName: valueOf(row, ["leaderName", "activityLeaderName", "principalName"], ""),
    leaderNames: listOf(valueOf(row, ["leaderNames"], [])),
    leaderPhone: valueOf(row, ["leaderPhone", "activityLeaderPhone", "principalPhone"], ""),
    checkInStatus: valueOf(row, ["checkInStatus", "signinStatus"], ""),
    attendanceStatus: valueOf(row, ["attendanceStatus", "arriveStatus"], ""),
    violationStatus: valueOf(row, ["violationStatus"], ""),
    checkInMethod: valueOf(row, ["checkInMethod", "signinMethod"], ""),
    checkInTime: valueOf(row, ["checkInTime", "signinTime"], ""),
    checkOutTime: valueOf(row, ["checkOutTime", "signoutTime"], ""),
    serviceMinutes: valueOf(row, ["serviceMinutes", "durationMinutes"], 0),
    slotId: valueOf(row, ["slotId"], ""),
    projectName: valueOf(row, ["projectName", "slotName"], ""),
    slotStartTime: valueOf(row, ["slotStartTime"], ""),
    slotEndTime: valueOf(row, ["slotEndTime"], ""),
    enrollmentId: valueOf(row, ["enrollmentId"], ""),
    runStatus: valueOf(row, ["runStatus"], ""),
    pointsAward: valueOf(row, ["pointsAward", "points"], ""),
    homeConfirmed: valueOf(row, ["homeConfirmed", "confirmHome"], false),
    canCheckIn: valueOf(row, ["canCheckIn"], false),
    canConfirmHome: valueOf(row, ["canConfirmHome"], false),
    canReview: valueOf(row, ["canReview"], false)
  };
}

function sortRecommendedActivities(list) {
  return list.slice().sort((a, b) => {
    const aHasQuota = a.hasQuota === null ? Number(a.quota || 0) > Number(a.joined || 0) : Boolean(a.hasQuota);
    const bHasQuota = b.hasQuota === null ? Number(b.quota || 0) > Number(b.joined || 0) : Boolean(b.hasQuota);
    if (aHasQuota !== bHasQuota) {
      return aHasQuota ? -1 : 1;
    }
    const aTime = Date.parse(`${a.date || ""} ${String(a.time || "").split("-")[0]}`.replace(/-/g, "/")) || 0;
    const bTime = Date.parse(`${b.date || ""} ${String(b.time || "").split("-")[0]}`.replace(/-/g, "/")) || 0;
    return bTime - aTime;
  });
}

function normalizeSlot(row) {
  const start = valueOf(row, ["startTime", "beginTime", "startAt"]);
  const end = valueOf(row, ["endTime", "finishTime", "endAt"]);
  const quota = valueOf(row, ["quota", "needCount", "capacity"], 0);
  const joined = valueOf(row, ["joined", "enrolledCount", "enrollCount", "signupCount"], 0);
  const date = datePart(start || valueOf(row, ["date", "activityDate"], ""));
  return {
    id: String(valueOf(row, ["id", "slotId", "projectId"], "")),
    name: valueOf(row, ["name", "projectName", "title"], "活动岗位"),
    startTime: start,
    endTime: end,
    date,
    dateShort: date ? `${date.slice(5, 7)}月${date.slice(8, 10)}日` : "",
    time: start && end ? `${minutePart(start)}-${minutePart(end)}` : valueOf(row, ["time", "timeRange"], ""),
    quota,
    joined,
    status: statusText(valueOf(row, ["statusName", "statusText", "status"]), {
      0: "未开始",
      1: "招募中",
      2: "已满员"
    }, Number(quota) && Number(joined) >= Number(quota) ? "已满员" : "招募中")
  };
}

function normalizeGroup(row) {
  return {
    id: String(valueOf(row, ["id", "groupId"], "")),
    no: valueOf(row, ["groupNo", "no"], ""),
    name: valueOf(row, ["name", "groupName"], "未命名小组"),
    leader: valueOf(row, ["leaderName", "leader"], ""),
    school: valueOf(row, ["school"], ""),
    members: valueOf(row, ["memberCount", "members"], 0),
    status: statusText(valueOf(row, ["statusName", "statusText", "status"]), {
      0: "待审核",
      1: "已通过",
      2: "已拒绝",
      3: "已解散"
    }, "待审核"),
    intro: valueOf(row, ["intro", "description", "summary"], "")
  };
}

function normalizeSquad(row) {
  return {
    id: String(valueOf(row, ["id", "squadId"], "")),
    type: valueOf(row, ["type", "squadType"], ""),
    name: valueOf(row, ["name", "squadName"], "未命名分队"),
    leader: valueOf(row, ["leaderName", "leader"], ""),
    leaderPhone: valueOf(row, ["leaderPhone", "phone"], ""),
    members: valueOf(row, ["memberCount", "members"], 0),
    memberCount: valueOf(row, ["memberCount", "members"], 0),
    memberLimit: valueOf(row, ["memberLimit"], ""),
    visible: valueOf(row, ["visibleFields", "visible"], ""),
    canApply: valueOf(row, ["canApply"], true),
    belonged: valueOf(row, ["belonged"], false),
    applications: recordsOf(valueOf(row, ["applications"], [])),
    memberList: recordsOf(valueOf(row, ["memberList", "volunteers", "members"], []))
  };
}

function normalizeNotice(row) {
  return {
    id: String(valueOf(row, ["id", "announcementId"], "")),
    title: valueOf(row, ["title"], "未命名公告"),
    date: datePart(valueOf(row, ["publishTime", "createTime", "date"], "")),
    type: valueOf(row, ["type", "category"], "公告"),
    summary: valueOf(row, ["summary", "content"], ""),
    content: valueOf(row, ["content", "summary"], ""),
    coverImageUrl: valueOf(row, ["coverImageUrl", "imageUrl"], ""),
    linkType: valueOf(row, ["linkType"], 0),
    linkUrl: valueOf(row, ["linkUrl"], "")
  };
}

function normalizeBanner(row) {
  return {
    id: String(valueOf(row, ["id", "bannerId"], "")),
    title: valueOf(row, ["title"], ""),
    subtitle: valueOf(row, ["subtitle", "summary"], ""),
    imageUrl: valueOf(row, ["imageUrl", "coverImageUrl"], ""),
    color: valueOf(row, ["color"], "#d83b3b"),
    linkType: valueOf(row, ["linkType"], 0),
    linkUrl: valueOf(row, ["linkUrl"], ""),
    appId: valueOf(row, ["appId", "targetAppId"], "")
  };
}

function normalizeMetric(row) {
  return {
    label: valueOf(row, ["label", "name", "title"], ""),
    value: valueOf(row, ["value", "count"], "0"),
    unit: valueOf(row, ["unit"], "")
  };
}

function normalizeSearch(row) {
  const type = valueOf(row, ["type", "category"], "");
  return {
    type,
    id: String(valueOf(row, ["id", "businessId"], "")),
    title: valueOf(row, ["title", "name"], ""),
    summary: valueOf(row, ["summary", "desc", "description"], ""),
    imageUrl: valueOf(row, ["imageUrl", "coverImageUrl", "coverUrl"], "")
  };
}

function normalizeServiceRecord(row) {
  const serviceMinutes = valueOf(row, ["serviceMinutes", "durationMinutes", "minutes"], 0);
  const activity = row && row.activity ? normalizeActivity(row.activity) : null;
  return {
    id: String(valueOf(row, ["id", "recordId", "attendanceId"], "")),
    activityId: String(valueOf(row, ["activityId"], activity ? activity.id : "")),
    activityTitle: valueOf(row, ["activityTitle", "activityName", "title"], activity ? activity.title : "未命名活动"),
    date: datePart(valueOf(row, ["date", "activityDate", "startTime"], activity ? activity.date : "")),
    time: valueOf(row, ["time", "timeRange"], activity ? activity.time : ""),
    checkInTime: valueOf(row, ["checkInTime", "signinTime"], ""),
    checkOutTime: valueOf(row, ["checkOutTime", "signoutTime"], ""),
    serviceMinutes,
    serviceText: minutesText(serviceMinutes),
    confirmStatus: valueOf(row, ["confirmStatus", "auditStatus"], "待秘书部确认"),
    pointsStatus: valueOf(row, ["pointsStatus"], "待发放"),
    points: valueOf(row, ["points", "grantedPoints"], 0)
  };
}

function normalizeManagedVolunteer(row = {}) {
  const status = valueOf(row, ["statusText", "attendanceStatusName", "attendanceStatus", "checkInStatus", "signinStatus"], "未签到");
  const statusClassMap = {
    已签到: "ok",
    正常: "ok",
    已签退: "purple",
    已到家: "brown",
    请假: "danger",
    迟到: "danger",
    缺席: "danger",
    未签到: "danger"
  };
  return {
    id: String(valueOf(row, ["volunteerId", "id", "userId"], "")),
    name: valueOf(row, ["name", "realName", "volunteerName"], ""),
    phone: valueOf(row, ["phone", "mobile", "phoneNumber"], ""),
    school: valueOf(row, ["school", "schoolName"], ""),
    remark: valueOf(row, ["remark", "roleName", "position"], ""),
    status,
    statusClass: valueOf(row, ["statusClass"], statusClassMap[status] || "danger")
  };
}

function normalizeManagedViolation(row = {}) {
  return {
    id: String(valueOf(row, ["id", "recordId"], "")),
    name: valueOf(row, ["name", "volunteerName", "realName"], ""),
    recorder: valueOf(row, ["recorder", "recorderName", "operatorName"], ""),
    detail: valueOf(row, ["detail", "reason", "content", "violationType"], ""),
    time: minutePart(valueOf(row, ["time", "createTime", "recordTime"], "")) || valueOf(row, ["time"], "")
  };
}

function normalizeManagedActivity(row = {}) {
  const start = valueOf(row, ["startTime", "activityStartTime", "beginTime", "startAt"]);
  const end = valueOf(row, ["endTime", "activityEndTime", "finishTime", "endAt"]);
  const volunteers = recordsOf(valueOf(row, ["volunteers", "volunteerList", "participants", "attendances", "enrollments", "roster"], []))
    .map(normalizeManagedVolunteer);
  const details = recordsOf(valueOf(row, ["violations", "violationRecords", "records"], []))
    .map(normalizeManagedViolation);
  return {
    id: String(valueOf(row, ["id", "activityId", "activityNo"], "")),
    name: valueOf(row, ["name", "title", "activityName"], "公益书屋项目书籍整理活动"),
    location: valueOf(row, ["location", "place", "address"], "御景雅苑（东方三路）"),
    time: start && end ? `${String(start).replace("T", " ").slice(0, 16)} - ${String(end).replace("T", " ").slice(5, 16)}` : valueOf(row, ["time", "timeRange"], "2024/10/20 09:59 - 10/20 20:00"),
    startTime: valueOf(row, ["actualStartTime", "startedAt"], ""),
    endTime: valueOf(row, ["actualEndTime", "finishedAt"], ""),
    joined: valueOf(row, ["joinedText", "joined", "enrolledCount", "participantCount"], volunteers.length ? `${volunteers.length}人` : "0人"),
    signed: valueOf(row, ["signedText", "signed", "signedCount", "checkInCount"], "0人"),
    checkedOut: valueOf(row, ["checkedOutText", "checkedOut", "checkOutCount"], "0人"),
    home: valueOf(row, ["homeText", "home", "homeConfirmedCount"], "0人"),
    phone: valueOf(row, ["phone", "leaderPhone", "contactPhone"], "15766508094"),
    volunteers,
    records: volunteers,
    details
  };
}

function boolOf(value, fallback = false) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  if (typeof value === "number") return value !== 0;
  return ["true", "1", "yes", "已实名", "已认证"].includes(String(value));
}

async function loadMyPermissions() {
  if (useMockApi()) return auth.getPermissions ? auth.getPermissions() : [];
  let payload;
  try {
    const response = await request({ url: ENDPOINTS.volunteer.organization.myPermissions });
    payload = payloadOf(response);
  } catch (error) {
    try {
      const legacyResponse = await request({ url: ENDPOINTS.volunteer.auth.myPermissions });
      payload = payloadOf(legacyResponse);
    } catch (legacyError) {
      try {
        const profileResponse = await request({ url: ENDPOINTS.volunteer.user.profile });
        payload = payloadOf(profileResponse);
      } catch (profileError) {
        const cached = auth.getPermissions ? auth.getPermissions() : [];
        if (cached.length) return cached;
        throw profileError;
      }
    }
  }
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.permissions)) return payload.permissions;
  if (Array.isArray(payload?.permissionCodes)) return payload.permissionCodes;
  if (Array.isArray(payload?.codes)) return payload.codes;
  return [];
}

function normalizeDateTimeForSubmit(value) {
  if (!value) return null;
  const text = String(value).trim().replace(/\s+/, "T");
  if (!text) return null;
  return text.length === 16 ? `${text}:00` : text;
}

function numberOrDefault(value, fallback) {
  if (value === "" || value === undefined || value === null) return fallback;
  const number = Number(value);
  return Number.isNaN(number) ? fallback : number;
}

function buildActivityPublishPayload(form = {}) {
  const startTime = normalizeDateTimeForSubmit(form.startTime);
  const endTime = normalizeDateTimeForSubmit(form.endTime);
  const enrollDeadline = normalizeDateTimeForSubmit(form.enrollDeadline) || startTime;
  const enrollOpenVolunteer = normalizeDateTimeForSubmit(form.enrollOpenVolunteer) || startTime;
  const lat = numberOrDefault(form.lat, null);
  const lng = numberOrDefault(form.lng, null);
  const payload = {
    title: form.title || form.name || "",
    coverImageUrl: form.coverImageUrl || null,
    location: form.location || "",
    lat,
    lng,
    content: form.content || "",
    requirement: form.requirement || "",
    startTime,
    endTime,
    enrollDeadline,
    cancelDeadline: normalizeDateTimeForSubmit(form.cancelDeadline) || enrollDeadline,
    pointsBase: numberOrDefault(form.pointsBase, 0),
    leaderMultiplier: numberOrDefault(form.leaderMultiplier, 1),
    managerMultiplier: numberOrDefault(form.managerMultiplier, 1),
    needAudit: numberOrDefault(form.needAudit, 0),
    enrollScope: numberOrDefault(form.enrollScope, 0),
    requireMinJoinCount: numberOrDefault(form.requireMinJoinCount, 0),
    requireMinJoinMinutes: numberOrDefault(form.requireMinJoinMinutes, 0),
    minProjects: 1,
    maxProjects: 1,
    enrollNotice: form.enrollNotice || "",
    contactName: form.contactName || "",
    contactPhone: form.contactPhone || "",
    publisherDeptName: form.publisherDeptName || "组织部",
    enrollOpenManager: normalizeDateTimeForSubmit(form.enrollOpenManager) || enrollOpenVolunteer,
    enrollOpenLeader: normalizeDateTimeForSubmit(form.enrollOpenLeader) || enrollOpenVolunteer,
    enrollOpenVolunteer,
    checkInRadiusM: numberOrDefault(form.checkInRadiusM, 500),
    slots: [{
      projectName: form.slotProjectName || "志愿者",
      startTime,
      endTime,
      needCount: numberOrDefault(form.slotNeedCount, 1)
    }]
  };
  if (lat === null || lng === null) {
    payload.lat = null;
    payload.lng = null;
  }
  return payload;
}

function normalizeUserProfile(row = {}) {
  const localUser = auth.getUser ? auth.getUser() : {};
  const registered = boolOf(valueOf(row, ["registered", "realNameVerified", "verified", "authStatus"], localUser.registered), false);
  const realName = valueOf(row, ["realName", "name"], localUser.realName || "");
  const nickName = valueOf(row, ["nickName", "nickname", "wxNickName", "wechatNickName"], localUser.nickName || "微信昵称");
  const phone = valueOf(row, ["phone", "mobile", "phoneNumber"], localUser.phone || "");
  const serviceMinutes = Number(valueOf(row, ["serviceMinutes", "durationMinutes"], 0)) || 0;
  const serviceHours = valueOf(row, ["hours", "serviceHoursText", "serviceDurationText"], serviceMinutes ? minutesText(serviceMinutes) : "0分钟");
  const politicalStatus = valueOf(row, ["politics", "politicalStatusName", "politicalStatus"], "");
  const grade = valueOf(row, ["gradeName", "grade"], "");
  return {
    displayName: registered && realName ? realName : nickName,
    registered,
    profile: {
      name: registered && realName ? realName : nickName,
      realName,
      nickName,
      no: valueOf(row, ["volunteerNo", "no", "code"], ""),
      role: registered ? "志愿者" : "游客",
      title: valueOf(row, ["title", "position", "duty"], registered ? "志愿者" : "未实名"),
      phone,
      group: valueOf(row, ["groupName", "group"], "未加入小组"),
      squad: valueOf(row, ["squadName", "squad"], "未归属分队"),
      hours: serviceHours,
      points: valueOf(row, ["points", "score"], 0),
      activities: valueOf(row, ["activityCount", "activities"], 0),
      realNameVerified: registered,
      honorMedal: boolOf(valueOf(row, ["honorMedal", "hasHonorMedal"], false), false),
      volunteerCard: registered
    },
    info: {
      name: realName || "",
      idType: valueOf(row, ["idTypeName", "idType"], registered ? "第二代居民身份证" : ""),
      idNo: valueOf(row, ["idCardNo", "idNo", "idNumber"], ""),
      phone,
      emergencyPhone: valueOf(row, ["emergencyContactPhone", "emergencyPhone"], ""),
      politics: String(politicalStatus || ""),
      school: valueOf(row, ["school"], ""),
      grade: String(grade || ""),
      address: valueOf(row, ["address", "contactAddress"], ""),
      nickName,
      avatar: valueOf(row, ["avatarUrl", "avatar"], localUser.avatarUrl || ""),
      volunteerCode: valueOf(row, ["ivolunteerCodeUrl", "volunteerCode"], "")
    }
  };
}

async function getAgreement() {
  if (useMockApi()) return mock.agreement;
  const response = await request({ url: ENDPOINTS.volunteer.auth.agreement });
  return payloadOf(response);
}

async function registerVolunteer(data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.auth.register,
    method: "POST",
    data
  });
}

async function listActivities() {
  if (useMockApi()) return sortRecommendedActivities(mock.activities.map(normalizeActivity));
  const size = 100;
  const first = await request({
    url: ENDPOINTS.volunteer.activity.activities,
    data: { page: 1, size }
  });
  const firstPayload = payloadOf(first) || {};
  let rows = recordsOf(first);
  const total = Number(firstPayload.total || rows.length);
  const pages = Math.min(Number(firstPayload.pages || Math.ceil(total / size) || 1), 10);
  for (let page = 2; page <= pages; page += 1) {
    const response = await request({
      url: ENDPOINTS.volunteer.activity.activities,
      data: { page, size }
    });
    const nextRows = recordsOf(response);
    rows = rows.concat(nextRows);
    if (!nextRows.length || rows.length >= total) break;
  }
  return sortRecommendedActivities(rows.map(normalizeActivity));
}

async function getActivity(id) {
  if (useMockApi()) {
    return mock.activities.find((item) => item.id === id) || mock.activities[0];
  }
  const response = await request({ url: ENDPOINTS.volunteer.activity.activityDetail(id) });
  const activity = normalizeActivity(payloadOf(response));
  activity.slots = recordsOf(activity.slots).map(normalizeSlot);
  if (!activity.slots.length && activity.timeSlots.length) {
    activity.slots = recordsOf(activity.timeSlots).map(normalizeSlot);
  }
  return activity;
}

async function enrollActivity(id, data) {
  if (useMockApi()) return { success: true };
  const body = Object.assign({}, data || {});
  if (!body.slotIds && body.sessionIds) {
    body.slotIds = body.sessionIds;
  }
  delete body.sessionIds;
  return request({
    url: ENDPOINTS.volunteer.activity.enroll(id),
    method: "POST",
    data: body
  });
}

async function cancelEnrollActivity(id) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.activity.cancelEnroll(id),
    method: "DELETE"
  });
}

async function proxyEnrollActivity(id, data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.activity.proxyEnrollments(id),
    method: "POST",
    data
  });
}

async function publishActivity(data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.activity.activities,
    method: "POST",
    data: buildActivityPublishPayload(data)
  });
}

// 上传活动封面：选好的本地临时图片传到 /v/files/upload?dir=activity，返回可访问 URL。
// wx.request 不能传文件，必须用 wx.uploadFile；这里复用 request.js 同款的 baseUrl + token 取法，
// 并按后端 {code,message,data:{url}} 包装层解析（res.data 是字符串，需 JSON.parse）。
function uploadActivityImage(tempFilePath) {
  if (useMockApi()) return Promise.resolve(tempFilePath);
  const app = getApp();
  const baseUrl = (app && app.globalData && app.globalData.apiBaseUrl) || "http://localhost:8080/api";
  const token = auth.getToken();
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${baseUrl}${ENDPOINTS.volunteer.files.upload}`,
      filePath: tempFilePath,
      name: "file",
      formData: { dir: "activity" },
      header: token ? { Authorization: token } : {},
      success(res) {
        let body = res.data;
        try { body = JSON.parse(res.data); } catch (e) { /* 保留原始字符串 */ }
        const wrapped = body && typeof body === "object" && body.code !== undefined;
        const ok2xx = res.statusCode >= 200 && res.statusCode < 300;
        if (!ok2xx || (wrapped && ![0, 200].includes(Number(body.code)))) {
          reject(new Error((wrapped && (body.message || body.msg)) || `上传失败（${res.statusCode}）`));
          return;
        }
        const data = wrapped && body.data ? body.data : body;
        const url = data && (data.url || (typeof data === "string" ? data : ""));
        if (!url) {
          reject(new Error("上传返回异常"));
          return;
        }
        resolve(url);
      },
      fail() {
        reject(new Error("网络连接失败，图片上传失败"));
      }
    });
  });
}

async function listMyActivities() {
  if (useMockApi()) return mock.myActivities.map(normalizeActivity);
  const response = await request({ url: ENDPOINTS.volunteer.activity.myActivities });
  return recordsOrSingle(response).map((row) => normalizeActivity(row.activity || row));
}

async function listManagedActivities() {
  if (useMockApi()) {
    return [{
      id: "43683435684",
      name: "公益书屋项目书籍整理活动",
      location: "御景雅苑（东方三路）",
      time: "2024/10/20 09:59 - 10/20 20:00"
    }];
  }
  const response = await request({ url: ENDPOINTS.volunteer.activity.managedActivities });
  return recordsOrSingle(response).map(normalizeManagedActivity);
}

async function getManagedActivity(id) {
  if (useMockApi()) {
    return normalizeManagedActivity({
      id,
      name: "公益书屋项目书籍整理活动",
      location: "御景雅苑（东方三路）",
      time: "2024/10/20 09:59 - 10/20 20:00",
      phone: "15766508094",
      volunteers: [
        { id: "1", name: "邝大程", phone: "1576650894", school: "雷州市第一中学", remark: "负责人", statusText: "已签到" },
        { id: "2", name: "黄灵媛", phone: "1576650894", school: "广东松山职业技术学院", remark: "管理团队", statusText: "未签到" }
      ],
      violations: [
        { name: "邝大程", recorder: "曹海铺", detail: "长时间交头接耳", time: "10:00:01" }
      ]
    });
  }
  const response = await request({ url: ENDPOINTS.volunteer.activity.managedActivityDetail(id) });
  const payload = payloadOf(response) || {};
  const source = payload.activity && typeof payload.activity === "object"
    ? Object.assign({}, payload.activity, {
      volunteers: payload.volunteers || payload.volunteerList || payload.participants || payload.attendances || payload.roster,
      violations: payload.violations || payload.violationRecords || payload.records
    })
    : payload;
  return normalizeManagedActivity(source);
}

function managedPost(url, data) {
  if (useMockApi()) return Promise.resolve({ success: true });
  return request({ url, method: "POST", data });
}

async function startManagedActivity(id) {
  return managedPost(ENDPOINTS.volunteer.activity.managedStart(id));
}

async function finishManagedActivity(id) {
  return managedPost(ENDPOINTS.volunteer.activity.managedFinish(id));
}

async function checkOutManagedActivity(id, data) {
  return managedPost(ENDPOINTS.volunteer.activity.managedCheckOuts(id), data);
}

async function updateManagedAttendance(id, volunteerId, data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.activity.managedAttendance(id, volunteerId),
    method: "PATCH",
    data
  });
}

async function recordManagedViolation(id, volunteerId, data) {
  return managedPost(ENDPOINTS.volunteer.activity.managedViolation(id, volunteerId), data);
}

async function updateManagedEvaluation(id, volunteerId, data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.activity.managedEvaluation(id, volunteerId),
    method: "PATCH",
    data
  });
}

async function submitManagedSummary(id, data) {
  return managedPost(ENDPOINTS.volunteer.activity.managedSummary(id), data);
}

async function checkInActivity(id, data) {
  if (useMockApi()) return { success: true, message: "静态 mock：签到成功" };
  return request({
    url: ENDPOINTS.volunteer.activity.checkIn(id),
    method: "POST",
    data
  });
}

async function confirmHomeActivity(id, data) {
  if (useMockApi()) return { success: true, message: "静态 mock：已确认到家" };
  return request({
    url: ENDPOINTS.volunteer.activity.confirmHome(id),
    method: "POST",
    data
  });
}

async function reviewActivity(id, data) {
  if (useMockApi()) return { success: true, message: "静态 mock：评价已提交" };
  return request({
    url: ENDPOINTS.volunteer.activity.review(id),
    method: "POST",
    data
  });
}

async function listServiceRecords() {
  if (useMockApi()) return mock.serviceRecords.map(normalizeServiceRecord);
  const response = await request({ url: ENDPOINTS.volunteer.activity.serviceRecords });
  return recordsOf(response).map(normalizeServiceRecord);
}

async function getUserProfile() {
  if (useMockApi()) return normalizeUserProfile(mock.profile);
  try {
    const response = await request({ url: ENDPOINTS.volunteer.user.profile });
    return normalizeUserProfile(payloadOf(response) || {});
  } catch (error) {
    return normalizeUserProfile({});
  }
}

async function updateUserProfile(data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.user.profile,
    method: "PATCH",
    data
  });
}

async function listGroups() {
  if (useMockApi()) return mock.groups;
  const response = await request({ url: ENDPOINTS.volunteer.organization.groups });
  return recordsOf(response).map(normalizeGroup);
}

async function createGroup(data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groups,
    method: "POST",
    data
  });
}

async function getGroup(id) {
  if (useMockApi()) return mock.groups.find((item) => item.id === id) || mock.groups[0];
  const response = await request({ url: ENDPOINTS.volunteer.organization.groupDetail(id) });
  return normalizeGroup(payloadOf(response));
}

async function joinGroup(id) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupJoin(id),
    method: "POST",
    data: {}
  });
}

async function leaveGroup(id) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupLeave(id),
    method: "POST",
    data: {}
  });
}

async function listGroupMembers(id) {
  if (useMockApi()) return [];
  const response = await request({ url: ENDPOINTS.volunteer.organization.groupMembers(id) });
  return recordsOrSingle(response);
}

async function listGroupJoinApplications(id) {
  if (useMockApi()) return [];
  const response = await request({ url: ENDPOINTS.volunteer.organization.groupJoinApplications(id) });
  return recordsOrSingle(response);
}

async function approveGroupMember(id, memberId) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupMemberApprove(id, memberId),
    method: "POST",
    data: {}
  });
}

async function rejectGroupMember(id, memberId) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupMemberReject(id, memberId),
    method: "POST",
    data: {}
  });
}

async function deleteGroupMember(id, memberId) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupMemberDelete(id, memberId),
    method: "DELETE"
  });
}

async function setGroupMemberAdmin(id, memberId, enabled) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.groupMemberAdmin(id, memberId),
    method: enabled ? "POST" : "DELETE",
    data: {}
  });
}

async function listSquads() {
  if (useMockApi()) return mock.squads;
  const response = await request({ url: ENDPOINTS.volunteer.organization.squads });
  return recordsOf(response).map(normalizeSquad);
}

async function getSquad(id) {
  if (useMockApi()) return mock.squads.find((item) => item.id === id) || mock.squads[0];
  const response = await request({ url: ENDPOINTS.volunteer.organization.squadDetail(id) });
  return normalizeSquad(payloadOf(response));
}

async function listStructure() {
  if (useMockApi()) return [];
  const response = await request({ url: ENDPOINTS.volunteer.organization.structure });
  const payload = payloadOf(response);
  if (Array.isArray(payload)) return payload;
  return recordsOf(response);
}

async function applySquad(id, data) {
  if (useMockApi()) return { success: true };
  return request({
    url: ENDPOINTS.volunteer.organization.squadApplications(id),
    method: "POST",
    data: data || {}
  });
}

async function listNotices() {
  if (useMockApi()) return mock.notices;
  const response = await request({ url: ENDPOINTS.volunteer.publicity.announcements });
  return recordsOf(response).map(normalizeNotice);
}

async function getNotice(id) {
  if (useMockApi()) return mock.notices.find((item) => item.id === id) || mock.notices[0];
  const response = await request({ url: ENDPOINTS.volunteer.publicity.announcementDetail(id) });
  return normalizeNotice(payloadOf(response));
}

async function loadHomeData() {
  if (useMockApi()) {
    return {
      banners: mock.banners,
      notices: mock.notices.slice(0, 2),
      dashboard: mock.dashboard,
      activities: mock.activities.slice(0, 3)
    };
  }

  try {
    const response = await request({ url: ENDPOINTS.volunteer.home });
    const payload = payloadOf(response) || {};
    let banners = recordsOf(payload.banners || payload.bannerList).map(normalizeBanner);
    let notices = recordsOf(payload.notices || payload.announcements).map(normalizeNotice);
    let activities = recordsOf(payload.activities || payload.recommendedActivities).map(normalizeActivity);
    if (!banners.length) {
      banners = await request({ url: ENDPOINTS.volunteer.publicity.banners })
        .then((res) => recordsOf(res).map(normalizeBanner))
        .catch(() => []);
    }
    if (!notices.length) {
      notices = await request({ url: ENDPOINTS.volunteer.publicity.announcements })
        .then((res) => recordsOf(res).map(normalizeNotice))
        .catch(() => []);
    }
    if (!activities.length) {
      activities = await request({ url: ENDPOINTS.volunteer.activity.activities })
        .then((res) => recordsOf(res).map(normalizeActivity))
        .catch(() => []);
    }
    return {
      banners,
      notices,
      dashboard: recordsOf(payload.dashboard || payload.metrics).map(normalizeMetric),
      activities
    };
  } catch (error) {
    const [banners, notices, activities] = await Promise.all([
      request({ url: ENDPOINTS.volunteer.publicity.banners }).then((res) => recordsOf(res).map(normalizeBanner)),
      request({ url: ENDPOINTS.volunteer.publicity.announcements }).then((res) => recordsOf(res).map(normalizeNotice)),
      request({ url: ENDPOINTS.volunteer.activity.activities }).then((res) => recordsOf(res).map(normalizeActivity))
    ]);
    return {
      banners,
      notices: notices.slice(0, 2),
      dashboard: mock.dashboard,
      activities: activities.slice(0, 3)
    };
  }
}

function mockSearchItems(keyword) {
  const text = (keyword || "").trim();
  if (!text) {
    return [];
  }

  return [
    ...mock.activities.map((item) => ({
      type: "activity",
      id: item.id,
      title: item.title,
      summary: null,
      imageUrl: item.imageUrl || "/assets/icons/activity-logo.png"
    })),
    ...mock.notices.map((item) => ({
      type: "announcement",
      id: item.id,
      title: item.title,
      summary: item.summary,
      imageUrl: item.imageUrl || item.coverImageUrl || "/assets/icons/activity-logo.png"
    })),
    ...mock.groups.map((item) => ({
      type: "group",
      id: item.id,
      title: item.name,
      summary: item.intro,
      imageUrl: null,
      searchText: item.no
    })),
    ...mock.squads.map((item) => ({
      type: "squad",
      id: item.id,
      title: item.name,
      summary: item.type,
      imageUrl: null
    }))
  ].filter((item) => {
    return String(item.title || "").includes(text) || String(item.searchText || "").includes(text);
  });
}

async function search(keyword, page = 1, size = 10) {
  if (useMockApi()) {
    const records = mockSearchItems(keyword).map(normalizeSearch);
    const start = (page - 1) * size;
    return {
      records: records.slice(start, start + size),
      total: records.length,
      page,
      size
    };
  }
  const response = await request({
    url: ENDPOINTS.volunteer.search,
    data: { keyword, page, size }
  });
  const payload = payloadOf(response) || {};
  const records = recordsOf(response).map(normalizeSearch);
  return {
    records,
    total: Number(payload.total || records.length),
    page: Number(payload.page || page),
    size: Number(payload.size || size)
  };
}

module.exports = {
  enrollActivity,
  cancelEnrollActivity,
  proxyEnrollActivity,
  checkInActivity,
  confirmHomeActivity,
  getAgreement,
  getActivity,
  getGroup,
  getNotice,
  getSquad,
  listActivities,
  listGroups,
  createGroup,
  joinGroup,
  leaveGroup,
  listGroupMembers,
  listGroupJoinApplications,
  approveGroupMember,
  rejectGroupMember,
  deleteGroupMember,
  setGroupMemberAdmin,
  listManagedActivities,
  listMyActivities,
  listNotices,
  listSquads,
  applySquad,
  listStructure,
  listServiceRecords,
  getManagedActivity,
  getUserProfile,
  loadMyPermissions,
  startManagedActivity,
  finishManagedActivity,
  checkOutManagedActivity,
  updateManagedAttendance,
  recordManagedViolation,
  updateManagedEvaluation,
  submitManagedSummary,
  updateUserProfile,
  loadHomeData,
  registerVolunteer,
  publishActivity,
  uploadActivityImage,
  reviewActivity,
  search,
  normalizeActivity
};
