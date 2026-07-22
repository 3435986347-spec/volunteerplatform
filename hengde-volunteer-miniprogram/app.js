App({
  globalData: {
    apiBaseUrl: "http://localhost:8080/api",
    useMockApi: false,
    // 真实手机号登录链路已接通（验证码登录/账密登录）；dev 登录仅本地联调显式开启，
    // 不再作为真实登录失败的兜底分支（绕过鉴权的洞）。需要时本地手动改回 true。
    devVolunteerLogin: false,
    currentRole: "visitor"
  }
});