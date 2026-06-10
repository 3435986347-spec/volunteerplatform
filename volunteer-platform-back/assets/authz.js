/* ============================================================
   前端鉴权/权限模型：hasPerm(identity, code)
   与网络层 api.js 分离——只负责「当前身份是否具备某权限点」的判定，不发请求。
   identity 形如 { isSuperAdmin, permissionCodes:[...] }（来自 GET /a/auth/me 或预览身份）。
   - 超管（isSuperAdmin 或 permissionCodes 含 '*'）命中一切；
   - code 为空 = 登录即可；
   - code 为数组 = 任一命中即可；
   - 否则精确包含判定。
   ============================================================ */
function hasPerm(identity, code) {
  if (!identity) return false;
  var codes = identity.permissionCodes || [];
  if (identity.isSuperAdmin || codes.indexOf('*') >= 0) return true;
  if (!code) return true; // 登录即可
  if (Array.isArray(code)) return code.some(function (c) { return codes.indexOf(c) >= 0; });
  return codes.indexOf(code) >= 0;
}

window.hasPerm = hasPerm;
