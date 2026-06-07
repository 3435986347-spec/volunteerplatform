const banners = [
  {
    id: "bn001",
    title: "推文跳转案例",
    subtitle: "点击复制推文链接",
    imageUrl: "/assets/activity/detail-cover-1.png",
    color: "#d83b3b",
    linkType: 1,
    linkUrl: "https://mp.weixin.qq.com/s/hengde-volunteer-preview"
  },
  {
    id: "bn002",
    title: "小程序页面跳转案例",
    subtitle: "点击进入志愿活动详情",
    imageUrl: "/assets/login/leizhou-hero-transparent.png",
    color: "#177245",
    linkType: 2,
    linkUrl: "/pages/activity/detail?id=act002"
  },
  {
    id: "bn003",
    title: "其他小程序跳转预留",
    subtitle: "已预留 appId + path",
    imageUrl: "/assets/activity/detail-cover-1.png",
    color: "#2f6fb2",
    linkType: 2,
    linkUrl: "pages/index/index",
    appId: "wx0000000000000000"
  }
];

const notices = [
  {
    id: "nt-static-preview",
    title: "恒德公益公告栏静态预览",
    date: "2026-05-29",
    type: "公告",
    summary: "这是一条无需启动后端即可查看的公告，包含封面图、正文和跳转配置。",
    content: "这是公告栏静态预览正文。用于检查公告列表、公告详情、封面图片展示，以及图片跳转配置在小程序中的展示效果。后台真实接口联调前，可以先用这条公告确认页面布局是否符合预期。",
    coverImageUrl: "/assets/activity/detail-cover-1.png",
    linkType: 2,
    linkUrl: "/pages/activity/detail?id=act002"
  },
  {
    id: "nt001",
    title: "关于启用志愿者实名注册的通知",
    date: "2026-05-25",
    type: "公告",
    summary: "请完成手机号验证、身份证二要素认证与志愿者协议签署。",
    coverImageUrl: "/assets/activity/detail-cover-1.png",
    linkType: 1,
    linkUrl: "https://example.com/notice-001"
  },
  {
    id: "nt002",
    title: "端午节关爱老人活动报名须知",
    date: "2026-05-24",
    type: "活动",
    summary: "报名成功后请加入活动群，按活动岗位时间准时到场。",
    coverImageUrl: "/assets/activity/detail-cover-1.png",
    linkType: 2,
    linkUrl: "/pages/activity/detail?id=act002"
  },
  {
    id: "nt003",
    title: "志愿小组创建审核说明",
    date: "2026-05-20",
    type: "组织",
    summary: "新建小组需后台审核，一名志愿者同时只能加入一个小组。",
    coverImageUrl: "",
    linkType: 0,
    linkUrl: ""
  }
];

const dashboard = [
  { label: "注册志愿者", value: "1,286", unit: "人" },
  { label: "活动场次", value: "342", unit: "场" },
  { label: "服务时长", value: "18,920", unit: "小时" },
  { label: "分队数量", value: "18", unit: "个" }
];

const agreement = {
  title: "雷州市恒德爱心公益协会志愿者服务协议",
  content: "本人自愿加入雷州市恒德爱心公益协会志愿服务活动，承诺遵守协会章程、活动纪律和安全要求，真实提交个人信息，服从现场负责人安排，并同意平台按活动管理需要保存报名、签到、服务时长和评价记录。"
};

const activities = [
  {
    id: "act001",
    no: "1010000001",
    title: "端午节关爱老人志愿服务",
    team: "秘书部",
    date: "2026-06-10",
    time: "08:30-11:30",
    enrollDeadline: "2026-06-09 22:00",
    place: "雷州市福利院",
    quota: 30,
    joined: 0,
    enrolledCount: 0,
    hasQuota: true,
    points: 3,
    status: "未开放",
    target: "全平台",
    contact: "陈同学 138****8012",
    summary: "协助现场秩序维护、物资分发与老人陪伴。",
    views: 86
  },
  {
    id: "act002",
    no: "1010000002",
    activityNo: "LLSW202506151002",
    title: "文明交通劝导行动",
    team: "组织部",
    coverImageUrl: "/assets/activity/detail-cover-1.png",
    coverImages: [
      "/assets/activity/detail-cover-1.png",
      "/assets/login/leizhou-hero-transparent.png"
    ],
    date: "2026-06-15",
    time: "17:00-19:00",
    enrollDeadline: "2026-06-14 22:00",
    place: "雷州西湖大道路口",
    latitude: 20.9143,
    longitude: 110.0967,
    quota: 20,
    joined: 12,
    enrolledCount: 12,
    hasQuota: true,
    points: 2,
    status: "报名中",
    target: "城区分队",
    contact: "李同学 136****6208",
    contactOrg: "雷州市恒德爱心公益协会",
    contactName: "李同学",
    contactPhone: "13612346208",
    summary: "协助路口文明交通宣传，报名人员需自备志愿服。",
    content: "协助交警和管理团队开展文明交通宣传、路口秩序维护、行人引导和现场安全提醒。",
    signupTimes: {
      manager: "2026/06/01 09:00",
      leader: "2026/06/01 12:00",
      volunteer: "2026/06/01 18:00"
    },
    pointsByRole: {
      manager: 65,
      leader: 55,
      volunteer: 50
    },
    timeSlots: [
      { name: "时间段一", start: "2026-06-15 17:00", end: "2026-06-15 18:00" },
      { name: "时间段二", start: "2026-06-15 18:00", end: "2026-06-15 19:00" }
    ],
    recruitInfo: {
      deadline: "2026-06-14 22:00",
      limit: "限城区分队志愿者",
      family: "适合亲子体验",
      student: "适合16岁以上中学生参与",
      requirement: "报名人员需穿着志愿者服装，听从现场管理团队安排，不接受迟到早退。"
    },
    guarantees: [
      "志愿者服装",
      "提供饮水",
      "志愿者保险",
      "交通补贴",
      "志愿服务工具"
    ],
    registrants: [
      { id: "u001", name: "周怡汐", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/01 18:20", postTime: "2026/06/15 17:00-19:00", checkinTime: "", checkoutTime: "" },
      { id: "u002", name: "曾嘉豪", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/01 18:32", postTime: "2026/06/15 17:00-19:00", checkinTime: "", checkoutTime: "" },
      { id: "u003", name: "黄欣", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/02 09:05", postTime: "2026/06/15 17:00-19:00", checkinTime: "", checkoutTime: "" },
      { id: "u004", name: "李朝凤", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/02 10:10", postTime: "2026/06/15 18:00-19:00", checkinTime: "", checkoutTime: "" },
      { id: "u005", name: "陈凯纯", avatar: "/assets/icons/activity-logo.png", signupTime: "2026/06/02 14:28", postTime: "2026/06/15 18:00-19:00", checkinTime: "", checkoutTime: "" }
    ],
    views: 132
  },
  {
    id: "act003",
    no: "1010000003",
    title: "爱心助学资料整理",
    team: "宣传部",
    date: "2026-06-18",
    time: "14:30-17:30",
    enrollDeadline: "2026-06-17 22:00",
    place: "恒德公益办公室",
    quota: 12,
    joined: 12,
    enrolledCount: 12,
    hasQuota: false,
    points: 2,
    status: "报名截止",
    target: "全平台",
    contact: "黄同学 139****3180",
    summary: "整理助学项目材料、拍摄归档照片。",
    views: 64
  },
  {
    id: "act004",
    no: "1010000004",
    title: "社区环保巡河志愿服务",
    team: "监察部",
    date: "2026-06-20",
    time: "08:00-10:30",
    enrollDeadline: "2026-06-19 22:00",
    place: "雷州青年运河沿线",
    quota: 24,
    joined: 18,
    enrolledCount: 18,
    hasQuota: true,
    points: 3,
    status: "活动中",
    target: "全平台",
    contact: "吴同学 137****2233",
    summary: "沿河开展垃圾清理、文明劝导与安全巡查。",
    views: 210
  },
  {
    id: "act005",
    no: "1010000005",
    title: "敬老院文艺陪伴活动",
    team: "宣传部",
    date: "2026-05-22",
    time: "09:00-11:00",
    enrollDeadline: "2026-05-21 22:00",
    place: "雷州市敬老院",
    quota: 16,
    joined: 16,
    enrolledCount: 16,
    hasQuota: false,
    points: 2,
    status: "已结束",
    target: "全平台",
    contact: "林同学 138****6621",
    summary: "陪伴老人开展文艺表演、互动游戏和环境整理。",
    views: 178
  }
];

const myActivities = [
  Object.assign({}, activities[1], {
    leaderName: "李同学",
    leaderPhone: "13612346208",
    checkInStatus: "待签到",
    attendanceStatus: "未开始",
    violationStatus: "无违规",
    checkInMethod: "",
    checkInTime: "",
    checkOutTime: "",
    serviceMinutes: 0,
    canCheckIn: true,
    canConfirmHome: false,
    canReview: false,
    homeConfirmed: false
  }),
  Object.assign({}, activities[3], {
    leaderName: "吴同学",
    leaderPhone: "13722232233",
    checkInStatus: "已签到",
    attendanceStatus: "正常到位",
    violationStatus: "无违规",
    checkInMethod: "定位自动签到",
    checkInTime: "2026-06-20 07:56",
    checkOutTime: "",
    serviceMinutes: 0,
    canCheckIn: false,
    canConfirmHome: true,
    canReview: false,
    homeConfirmed: false
  }),
  Object.assign({}, activities[4], {
    leaderName: "林同学",
    leaderPhone: "13866626621",
    checkInStatus: "已完成",
    attendanceStatus: "正常到位",
    violationStatus: "无违规",
    checkInMethod: "扫码签到",
    checkInTime: "2026-05-22 08:51",
    checkOutTime: "2026-05-22 11:05",
    serviceMinutes: 134,
    canCheckIn: false,
    canConfirmHome: false,
    canReview: true,
    homeConfirmed: true
  })
];

const serviceRecords = [
  {
    id: "sr001",
    activityId: "act005",
    activityTitle: "敬老院文艺陪伴活动",
    date: "2026-05-22",
    time: "09:00-11:00",
    checkInTime: "2026-05-22 08:51",
    checkOutTime: "2026-05-22 11:05",
    serviceMinutes: 134,
    confirmStatus: "秘书部已确认",
    pointsStatus: "积分已发放",
    points: 2
  },
  {
    id: "sr002",
    activityId: "act004",
    activityTitle: "社区环保巡河志愿服务",
    date: "2026-06-20",
    time: "08:00-10:30",
    checkInTime: "2026-06-20 07:56",
    checkOutTime: "",
    serviceMinutes: 0,
    confirmStatus: "待签退",
    pointsStatus: "待发放",
    points: 0
  }
];

const groups = [
  {
    id: "grp001",
    no: "G202605001",
    name: "萤火志愿小组",
    leader: "林可",
    school: "雷州一中",
    members: 18,
    status: "已通过",
    intro: "常态参与敬老、助学与城市服务活动。"
  },
  {
    id: "grp002",
    no: "G202605002",
    name: "向阳服务队",
    leader: "吴嘉",
    school: "雷州二中",
    members: 11,
    status: "审核中",
    intro: "面向学生志愿者的小组，周末可统一报名。"
  }
];

const squads = [
  {
    id: "tm001",
    type: "学校分队",
    name: "雷州一中分队",
    leader: "陈晓",
    members: 126,
    visible: "姓名、学校、手机号",
    canApply: true
  },
  {
    id: "tm002",
    type: "乡镇分队",
    name: "白沙镇分队",
    leader: "黄敏",
    members: 84,
    visible: "姓名、所在乡镇",
    canApply: true
  },
  {
    id: "tm003",
    type: "学校分队",
    name: "雷州二中分队",
    leader: "周远",
    members: 98,
    visible: "姓名、学校、手机号",
    canApply: false
  }
];

const posts = [
  {
    id: "post001",
    tab: "官方",
    author: "宣传部",
    title: "端午活动花絮",
    body: "志愿者们协助完成慰问物资分发，并陪伴老人开展小游戏。",
    likes: 128,
    comments: 26
  },
  {
    id: "post002",
    tab: "最热",
    author: "林可",
    title: "第一次参与交通劝导",
    body: "现场老师讲解很清楚，同组成员互相提醒，体验很好。",
    likes: 86,
    comments: 14
  }
];

const profile = {
  name: "邝大程",
  no: "3156353651",
  role: "志愿者",
  title: "宣传部部长",
  phone: "138****7788",
  group: "未加入小组",
  squad: "未归属分队",
  hours: "32小时32分钟",
  points: 3125,
  activities: 3,
  realNameVerified: true,
  honorMedal: true
};

module.exports = {
  agreement,
  banners,
  notices,
  dashboard,
  activities,
  myActivities,
  serviceRecords,
  groups,
  squads,
  posts,
  profile
};
