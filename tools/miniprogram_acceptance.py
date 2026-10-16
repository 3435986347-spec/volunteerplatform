#!/usr/bin/env python3
"""
小程序联调验收：专用测试数据 + 证据采集（只依赖 Python 3 标准库）。

对应小程序侧 2026-10-13 的验收单：每一项都要带「对象 ID / 角色、请求路径与参数、时间、HTTP / 业务 code、
traceId、脱敏结果」。手工一条条记既慢又容易漏，这个脚本把它做成可重复的三步：

    seed     建专用测试数据（奖惩 25 条、问卷、考试、活动 + 名单公示 + 未报名负责人、社区帖子、投诉、
             受限账号、可选的两张协会证书），把建出来的每个 ID 写进清单文件
    verify   照清单逐项调接口，输出证据表（Markdown）与原始记录（JSON）；每条带期望与是否符合
    cleanup  撤掉 seed 建的东西（帖子、活动、问卷、考试、受限处置、负责人的管理团队标记）

⚠️ seed / cleanup 会写库。对线上跑之前确认 --base 指的是哪台服务器；脚本会把地址打出来、要求输入 yes。

身份（都是本地登录拿到的 token，脚本不收密码、不把 token 写进任何输出）：
    HD_TOKEN_NORMAL      普通实名志愿者，手机号已验证（社区要求）
    HD_TOKEN_GUEST       未实名游客
    HD_TOKEN_LEADER      实名志愿者，seed 会把他设成管理团队并指派为负责人、但不报名
    HD_TOKEN_RESTRICTED  实名志愿者，seed 会给他开一张「拒绝使用本程序」7 天的处罚
    HD_ADMIN_USER / HD_ADMIN_PASS   后台超管（不设就交互输入，密码不回显）
可选（建两张协会证书，一张 fileReady=false、一张 =true）：
    HD_CERT_TEMPLATE_PDF  协会盖章的样本 PDF 路径（没有它就不建证书——不造假章）
    HD_NORMAL_PHONE / HD_NORMAL_NAME  普通志愿者的手机号与实名姓名（补录按手机号 + 姓名定位）

本地开发库可以用 --dev-login 代替上面四个 token（只认 localhost，服务端也必须开着开发登录）。

用法：
    python3 tools/miniprogram_acceptance.py seed    --base http://118.145.69.25/api
    python3 tools/miniprogram_acceptance.py verify  --base http://118.145.69.25/api
    python3 tools/miniprogram_acceptance.py cleanup --base http://118.145.69.25/api
清单默认写在当前目录的 acceptance-manifest.json，证据写在 acceptance-evidence-<时间>.md / .json。
"""
import argparse
import datetime as dt
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

FMT = "%Y-%m-%d %H:%M:%S"
ROLES = ("NORMAL", "GUEST", "LEADER", "RESTRICTED")
ROLE_LABEL = {"NORMAL": "普通实名志愿者", "GUEST": "未实名游客", "LEADER": "未报名负责人",
              "RESTRICTED": "受限账号（拒绝使用本程序）", "ADMIN": "后台超管", "ANON": "未登录"}
# 1×1 透明 PNG：上传校验看文件头
PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                    "1f15c4890000000d49444154789c63000100000500010d0a2db40000000049454e44ae426082")


def now():
    return dt.datetime.now().strftime(FMT)


class Resp:
    def __init__(self, http, code, data, message, trace, raw):
        self.http, self.code, self.data, self.message, self.trace, self.raw = http, code, data, message, trace, raw

    @property
    def ok(self):
        return self.http == 200 and self.code == 200


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, token=None, body=None, multipart=None):
        url = self.base + path
        headers = {}
        data = None
        if multipart is not None:
            boundary = "----hdacceptance" + uuid.uuid4().hex
            name, filename, ctype, content = multipart
            data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                    f"Content-Type: {ctype}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = token
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, raw, trace = r.status, r.read(), r.headers.get("X-Trace-Id")
        except urllib.error.HTTPError as e:
            status, raw, trace = e.code, e.read(), e.headers.get("X-Trace-Id")
        except urllib.error.URLError as e:
            return Resp(0, -1, None, f"连不上：{e.reason}", None, b"")
        try:
            j = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            j = None
        if isinstance(j, dict) and "code" in j:
            return Resp(status, j.get("code"), j.get("data"), j.get("message"), trace, raw)
        return Resp(status, status, j, None, trace, raw)


# ---------------------------------------------------------------- 脱敏摘要

def redact_url(u):
    if not isinstance(u, str):
        return u
    p = urllib.parse.urlsplit(u)
    if p.query:
        keys = sorted({k for k, _ in urllib.parse.parse_qsl(p.query)})
        return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path, "", "")) + "?(" + ",".join(keys) + " 略)"
    return u


def summarize(data):
    """只留计数、ID、标志位与业务字段名——不出姓名、电话、证件号、签名参数。"""
    if data is None:
        return "data=null（出参省略 null 字段时即「没有这个键」）"
    if isinstance(data, str):
        return redact_url(data)[:160]
    if isinstance(data, list):
        return f"数组 len={len(data)}" + (f" 首项字段={sorted(data[0].keys())[:8]}" if data and isinstance(data[0], dict) else "")
    if isinstance(data, dict):
        if "records" in data:
            recs = data.get("records") or []
            ids = [str(r.get("id")) for r in recs[:5] if isinstance(r, dict) and "id" in r]
            extra = ""
            if recs and isinstance(recs[0], dict) and "appealable" in recs[0]:
                extra = f" appealable=true 条数={sum(1 for r in recs if r.get('appealable'))}"
            if recs and isinstance(recs[0], dict) and "fileReady" in recs[0]:
                extra = " fileReady=" + ",".join(f"{r.get('id')}:{r.get('fileReady')}" for r in recs[:5])
            return (f"分页 total={data.get('total')} page={data.get('page')} size={data.get('size')} "
                    f"pages={data.get('pages')} 本页={len(recs)} id={ids}{extra}")
        keep = {k: v for k, v in data.items() if k in (
            "id", "title", "status", "uploadable", "photoCount", "schemaVersion", "build", "database",
            "eligible", "submitted", "fileReady", "appealable", "appealStatus", "key", "topic", "registered")}
        return f"对象 字段={sorted(data.keys())[:12]}" + (f" {json.dumps(keep, ensure_ascii=False)[:200]}" if keep else "")
    return str(data)[:160]


# ---------------------------------------------------------------- 证据

class Evidence:
    def __init__(self):
        self.rows = []

    def add(self, item, role, who, method, path, resp, expect=None, note=None):
        verdict = ""
        if expect is not None:
            verdict = "✓" if expect(resp) else "✗"
        row = {"time": now(), "item": item, "role": ROLE_LABEL.get(role, role), "subject": who,
               "request": f"{method} {path}", "http": resp.http, "code": resp.code, "traceId": resp.trace,
               "message": resp.message, "result": note or summarize(resp.data), "verdict": verdict}
        self.rows.append(row)
        mark = verdict or "·"
        print(f"  {mark} [{item}] {role} {method} {path} → HTTP {resp.http} / {resp.code} {resp.message or ''}"[:220])
        return resp

    def write(self, base, prefix):
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        md = f"{prefix}-{stamp}.md"
        js = f"{prefix}-{stamp}.json"
        with open(js, "w", encoding="utf-8") as f:
            json.dump({"base": base, "rows": self.rows}, f, ensure_ascii=False, indent=2)
        bad = [r for r in self.rows if r["verdict"] == "✗"]
        with open(md, "w", encoding="utf-8") as f:
            f.write(f"# 小程序联调验收证据\n\n服务地址：`{base}`　生成时间：{now()}（北京时间）\n\n")
            f.write(f"共 {len(self.rows)} 条，符合期望 {sum(1 for r in self.rows if r['verdict'] == '✓')}、"
                    f"不符合 {len(bad)}、未设期望 {sum(1 for r in self.rows if not r['verdict'])}。\n\n")
            f.write("| 时间 | 验收项 | 角色 | 对象 | 请求 | HTTP | code | traceId | 结果（脱敏） | 判定 |\n")
            f.write("|---|---|---|---|---|---|---|---|---|---|\n")
            for r in self.rows:
                res = (r["message"] + "；" if r["message"] and r["message"] != "成功" else "") + str(r["result"])
                res = res.replace("|", "\\|").replace("\n", " ")
                f.write(f"| {r['time']} | {r['item']} | {r['role']} | {r['subject'] or ''} | `{r['request']}` | "
                        f"{r['http']} | {r['code']} | {r['traceId'] or ''} | {res} | {r['verdict']} |\n")
        print(f"\n证据：{md}\n原始记录：{js}")
        return bad


# ---------------------------------------------------------------- 身份

def admin_login(api, args):
    user = os.environ.get("HD_ADMIN_USER") or input("后台超管账号：")
    pw = os.environ.get("HD_ADMIN_PASS") or getpass.getpass("后台超管密码（不回显）：")
    r = api.call("POST", "/a/auth/login", body={"username": user, "password": pw})
    if not r.ok:
        sys.exit(f"后台登录失败：HTTP {r.http} / {r.code} {r.message}")
    return r.data if isinstance(r.data, str) else (r.data or {}).get("token")


def volunteer_tokens(api, args):
    tokens = {}
    if args.dev_login:
        host = urllib.parse.urlsplit(args.base).hostname
        if host not in ("localhost", "127.0.0.1"):
            sys.exit("--dev-login 只用于本机开发库")
        for role in ROLES:
            r = api.call("POST", "/v/auth/login/dev",
                         body={"key": f"acc-{role.lower()}", "registered": role != "GUEST"})
            if not r.ok:
                sys.exit(f"开发登录失败（{role}）：{r.message}")
            tokens[role] = r.data["token"]
        return tokens
    for role in ROLES:
        t = os.environ.get("HD_TOKEN_" + role)
        if t:
            tokens[role] = t
    missing = [r for r in ROLES if r not in tokens]
    if missing:
        print("⚠️ 没给这些身份的 token，相关检查会跳过：" + ", ".join("HD_TOKEN_" + m for m in missing))
    return tokens


def confirm_write(args, what):
    print(f"\n即将对 {args.base} 执行「{what}」，会写库。")
    if args.yes:
        return
    if input("确认请输入 yes：").strip() != "yes":
        sys.exit("已取消")


def load_manifest(path):
    if not os.path.exists(path):
        sys.exit(f"找不到清单 {path}——先跑 seed")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_manifest(path, m):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
    print(f"\n清单：{path}")


# ---------------------------------------------------------------- seed

def seed(api, args):
    confirm_write(args, "建专用测试数据")
    ev = Evidence()
    admin = admin_login(api, args)
    tok = volunteer_tokens(api, args)
    tag = dt.datetime.now().strftime("%m%d%H%M")
    label = f"【联调测试 {tag}】"
    m = {"tag": tag, "base": args.base, "createdAt": now(), "volunteers": {}, "created": {}}

    def step(item, role, method, path, token, body=None, multipart=None, who=None):
        r = api.call(method, path, token, body, multipart)
        ev.add(item, role, who, method, path, r, expect=lambda x: x.ok)
        return r

    # 0) 各身份的志愿者 ID（受限账号要在开处罚之前取：开了之后资料接口就进不去了）
    for role, t in tok.items():
        r = step("身份", role, "GET", "/v/user/profile", t)
        if r.ok:
            m["volunteers"][role] = {"id": str(r.data.get("no")), "registered": r.data.get("registered")}
    vid = {role: v["id"] for role, v in m["volunteers"].items()}

    # 1) 奖惩：普通志愿者 25 条已生效处罚（超管持终审权，开即通过）→ 3 页，第 2 页全是可申诉
    if "NORMAL" in vid:
        ids = []
        for i in range(25):
            r = step("奖惩", "ADMIN", "POST", "/a/honor/reward-punishes", admin,
                     {"volunteerId": vid["NORMAL"], "type": 2, "category": "违规", "title": f"{label}处罚 {i + 1:02d}"},
                     who=f"志愿者 {vid['NORMAL']}")
            if r.ok:
                ids.append(str(r.data))
        m["created"]["rewardPunishIds"] = ids

    # 2) 问卷：通用场景、七种题型，发布
    r = step("问卷", "ADMIN", "POST", "/a/organization/forms", admin, {
        "title": f"{label}七种题型问卷",
        "questions": [
            {"type": 1, "title": "来自哪里", "options": ["雷城", "客路"]},
            {"type": 2, "title": "擅长", "options": ["摄影", "写作", "急救"], "maxSelect": 2},
            {"type": 3, "title": "参加过活动"},
            {"type": 4, "title": "学校", "maxLength": 30},
            {"type": 5, "title": "说说你的想法", "required": False},
            {"type": 6, "title": "附件", "required": False, "maxFiles": 1},
            {"type": 7, "title": "方便的日期", "required": False}]})
    if r.ok:
        fid = str(r.data)
        m["created"]["formId"] = fid
        step("问卷", "ADMIN", "POST", f"/a/organization/forms/{fid}/publish", admin)

    # 3) 考试：已有开放中的试卷就用它（不去停协会正在用的那份）；没有才建一份测试卷
    r = step("考试", "ADMIN", "GET", "/a/organization/exam-papers?status=1&page=1&size=10", admin)
    if r.ok and (r.data or {}).get("records"):
        m["created"]["examPaperId"] = str(r.data["records"][0]["id"])
        m["created"]["examPaperCreated"] = False
    else:
        r = step("考试", "ADMIN", "POST", "/a/organization/exam-papers", admin, {
            "title": f"{label}临时负责人考试", "passScore": 60, "qualificationMonths": 12,
            "questions": [
                {"type": 1, "title": "集合迟到应当", "options": ["不管", "联系负责人"], "score": 40, "answer": "B"},
                {"type": 3, "title": "负责人可以提前离场", "score": 30, "answer": False},
                {"type": 5, "title": "说说怎么组织签到", "score": 30, "answer": "先点名再核对名单"}]})
        if r.ok:
            pid = str(r.data)
            m["created"]["examPaperId"] = pid
            m["created"]["examPaperCreated"] = True
            step("考试", "ADMIN", "POST", f"/a/organization/exam-papers/{pid}/publish", admin)

    # 4) 活动：两个时段 → 普通志愿者补录进两个时段 → 未报名的负责人 → 确认名单 → 负责人打开相册
    start = (dt.datetime.now() + dt.timedelta(days=5)).replace(hour=9, minute=0, second=0, microsecond=0)
    afternoon = start.replace(hour=14)
    r = step("活动", "ADMIN", "POST", "/a/activity/activities", admin, {
        "title": f"{label}两时段活动", "location": "雷州市西湖公园",
        "startTime": start.strftime(FMT), "endTime": (afternoon + dt.timedelta(hours=2)).strftime(FMT),
        "slots": [
            {"projectName": "上午场", "startTime": start.strftime(FMT),
             "endTime": (start + dt.timedelta(hours=2)).strftime(FMT), "needCount": 10},
            {"projectName": "下午场", "startTime": afternoon.strftime(FMT),
             "endTime": (afternoon + dt.timedelta(hours=2)).strftime(FMT), "needCount": 10}]})
    if r.ok:
        aid = str(r.data)
        m["created"]["activityId"] = aid
        detail = step("活动", "ADMIN", "GET", f"/a/activity/activities/{aid}", admin)
        slot_ids = [s["id"] for s in (detail.data or {}).get("slots", [])] if detail.ok else []
        m["created"]["slotIds"] = [str(s) for s in slot_ids]
        if "NORMAL" in vid and slot_ids:
            step("活动", "ADMIN", "POST", f"/a/activity/activities/{aid}/enrollments", admin,
                 {"volunteerId": vid["NORMAL"], "slotIds": slot_ids}, who=f"志愿者 {vid['NORMAL']}")
        if "LEADER" in vid:
            info = step("活动", "ADMIN", "GET", f"/a/user/volunteers/{vid['LEADER']}", admin, who=f"志愿者 {vid['LEADER']}")
            was = (info.data or {}).get("managerFlag") if info.ok else None
            m["created"]["leaderManagerFlagBefore"] = was
            if str(was) != "1":
                step("活动", "ADMIN", "PUT", f"/a/organization/volunteers/{vid['LEADER']}/manager-flag", admin,
                     {"flag": 1}, who=f"志愿者 {vid['LEADER']}")
            step("活动", "ADMIN", "POST", f"/a/activity/activities/{aid}/leaders", admin,
                 {"leaderType": 1, "refId": vid["LEADER"]}, who=f"志愿者 {vid['LEADER']}（未报名）")
            ar = step("相册", "LEADER", "GET", f"/v/activity/activities/{aid}/album", tok["LEADER"],
                      who=f"志愿者 {vid['LEADER']}")
            if ar.ok:
                m["created"]["albumId"] = str(ar.data.get("id"))
        step("公示", "ADMIN", "POST", f"/a/activity/activities/{aid}/roster/publish", admin)

    # 5) 社区：普通志愿者发 12 条帖子（每页 10 条时有第 2 页）
    if "NORMAL" in tok:
        posts = []
        for i in range(12):
            r = step("社区", "NORMAL", "POST", "/v/social/posts", tok["NORMAL"],
                     {"content": f"{label}测试帖子 {i + 1:02d}", "visibility": 0}, who=f"志愿者 {vid.get('NORMAL')}")
            if r.ok:
                posts.append(str(r.data))
        m["created"]["postIds"] = posts

    # 6) 投诉：3 条（每人每天上限 5；每页 2 条时有第 2 页）
    if "NORMAL" in tok:
        cids = []
        for i in range(3):
            r = step("投诉", "NORMAL", "POST", "/v/data/complaints", tok["NORMAL"],
                     {"type": 1, "category": 1, "content": f"{label}测试投诉 {i + 1}"}, who=f"志愿者 {vid.get('NORMAL')}")
            if r.ok:
                cids.append(str(r.data))
        m["created"]["complaintIds"] = cids

    # 7) 协会证书（可选）：两场历史活动各补录一次 → 两张证书；下载其中一张让它 fileReady=true
    pdf = os.environ.get("HD_CERT_TEMPLATE_PDF")
    phone, name = os.environ.get("HD_NORMAL_PHONE"), os.environ.get("HD_NORMAL_NAME")
    if pdf and phone and name and "NORMAL" in tok:
        with open(pdf, "rb") as f:
            pdf_bytes = f.read()
        certs = []
        for k in (1, 2):
            past = (dt.datetime.now() - dt.timedelta(days=20 + k)).replace(hour=9, minute=0, second=0, microsecond=0)
            r = step("证书", "ADMIN", "POST", "/a/activity/activities/historical", admin, {
                "title": f"{label}证书用历史活动 {k}", "location": "雷州市西湖公园",
                "startTime": past.strftime(FMT), "endTime": (past + dt.timedelta(hours=2)).strftime(FMT),
                "slots": [{"projectName": "全天", "startTime": past.strftime(FMT),
                           "endTime": (past + dt.timedelta(hours=2)).strftime(FMT), "needCount": 0}]})
            if not r.ok:
                continue
            haid = str(r.data)
            d = step("证书", "ADMIN", "GET", f"/a/activity/activities/{haid}", admin)
            sid = d.data["slots"][0]["id"] if d.ok else None
            up = step("证书", "ADMIN", "POST", "/a/honor/certificate-templates/file", admin,
                      multipart=("file", "template.pdf", "application/pdf", pdf_bytes))
            if up.ok:
                step("证书", "ADMIN", "POST", "/a/honor/certificate-templates", admin,
                     {"activityId": haid, "name": f"{label}样本 {k}", "fileKey": up.data, "enabled": 1})
            b = step("证书", "ADMIN", "POST", f"/a/activity/activities/{haid}/backfills", admin,
                     {"phone": phone, "name": name, "slotId": sid, "reason": f"{label}证书验收"},
                     who=f"志愿者 {vid.get('NORMAL')}")
            if b.ok:
                step("证书", "ADMIN", "POST", f"/a/activity/backfills/{b.data}/approve", admin)
            certs.append({"activityId": haid})
        time.sleep(1.5)
        lst = step("证书", "NORMAL", "GET", "/v/honor/certificates?page=1&size=50", tok["NORMAL"])
        mine = [c for c in ((lst.data or {}).get("records") or []) if str(c.get("activityId")) in {x["activityId"] for x in certs}]
        m["created"]["certificateIds"] = [str(c["id"]) for c in mine]
        if mine:
            step("证书", "NORMAL", "GET", f"/v/honor/certificates/{mine[0]['id']}/file", tok["NORMAL"],
                 who=f"证书 {mine[0]['id']}（让它变成 fileReady=true）")
            m["created"]["certificateReadyId"] = str(mine[0]["id"])
            if len(mine) > 1:
                m["created"]["certificateNotReadyId"] = str(mine[1]["id"])
    else:
        print("  · 跳过协会证书：要 HD_CERT_TEMPLATE_PDF（协会盖章样本）+ HD_NORMAL_PHONE + HD_NORMAL_NAME")

    # 8) 受限账号：「拒绝使用本程序」7 天（超管开即生效）——放最后，前面的步骤要用他的资料接口
    if "RESTRICTED" in vid:
        r = step("受限账号", "ADMIN", "POST", "/a/honor/reward-punishes", admin,
                 {"volunteerId": vid["RESTRICTED"], "type": 2, "category": "违规", "title": f"{label}受限账号处罚",
                  "sanctionScope": 3, "sanctionDays": 7}, who=f"志愿者 {vid['RESTRICTED']}")
        if r.ok:
            m["created"]["restrictTicketId"] = str(r.data)

    save_manifest(args.manifest, m)
    bad = ev.write(args.base, "acceptance-seed")
    if bad:
        print(f"⚠️ {len(bad)} 步没成功，见证据表里标 ✗ 的行")


# ---------------------------------------------------------------- verify

def verify(api, args):
    m = load_manifest(args.manifest)
    ev = Evidence()
    tok = volunteer_tokens(api, args)
    vid = {role: v["id"] for role, v in m.get("volunteers", {}).items()}
    c = m.get("created", {})
    ok = lambda r: r.ok
    rejected = lambda r: r.http in (400, 403) and r.code in (400, 403)

    def check(item, role, method, path, expect=ok, note=None, body=None, multipart=None):
        if role not in ("ANON",) and role not in tok:
            return None
        r = api.call(method, path, tok.get(role), body, multipart)
        return ev.add(item, role, f"志愿者 {vid[role]}" if role in vid else None, method, path, r, expect, note)

    # ---- 部署探针（不登录）----
    check("部署", "ANON", "GET", "/actuator/info",
          expect=lambda r: r.http == 200 and isinstance(r.data, dict) and "database" in r.data,
          note=None)
    check("部署", "ANON", "GET", "/v/user/volunteer-cards/verify?token=probe", expect=lambda r: r.http == 400)
    check("部署", "ANON", "GET", "/share/files/probe", expect=lambda r: r.http == 400)
    check("部署", "NORMAL", "GET", "/v/__no_such_endpoint__", expect=lambda r: r.http == 404)

    # ---- 志愿者证：用一张真实有效的证件做匿名核验（无效令牌被拒证明不了核验本身是好的）----
    card = check("志愿者证", "NORMAL", "GET", "/v/user/volunteer-card")
    qr = (card.data or {}).get("qrContent") if card and card.ok else None
    token = None
    if qr:
        token = qr.split("hengde-volunteer-card:", 1)[1] if qr.startswith("hengde-volunteer-card:") else \
            dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(qr).query)).get("token")
    if token:
        r = api.call("GET", "/v/user/volunteer-cards/verify?token=" + urllib.parse.quote(token))
        # 令牌等同于这张证件，证据里不记原文
        ev.add("志愿者证", "ANON", f"志愿者 {vid.get('NORMAL')} 的证件", "GET",
               "/v/user/volunteer-cards/verify?token=(本人证件令牌，略)", r,
               expect=lambda x: x.ok and "*" in str((x.data or {}).get("maskedName", ""))
               and not {"phone", "realName", "idCard"} & set((x.data or {}).keys()),
               note=f"姓名脱敏={(r.data or {}).get('maskedName')} 字段={sorted((r.data or {}).keys())}")

    # ---- 4 证书 ----
    ready, not_ready = c.get("certificateReadyId"), c.get("certificateNotReadyId")
    lst = check("证书", "NORMAL", "GET", "/v/honor/certificates?page=1&size=50")
    if not_ready:
        before = next((x for x in (lst.data or {}).get("records", []) if str(x["id"]) == not_ready), {}) if lst else {}
        ev.add("证书", "NORMAL", f"证书 {not_ready}", "—", "（列表中这张）", Resp(200, 200, before, None, None, b""),
               expect=lambda r: r.data.get("fileReady") is False, note=f"fileReady={before.get('fileReady')}")
        u1 = check("证书", "NORMAL", "GET", f"/v/honor/certificates/{not_ready}/file",
                   note=None)
        time.sleep(1.2)
        u2 = check("证书", "NORMAL", "GET", f"/v/honor/certificates/{not_ready}/file")
        if u1 and u2 and u1.ok and u2.ok:
            ev.add("证书", "NORMAL", f"证书 {not_ready}", "—", "（两次签名比对）", Resp(200, 200, None, None, None, b""),
                   expect=lambda r: u1.data != u2.data,
                   note=f"两次 URL 不同={u1.data != u2.data}；有效期参数={[k for k, _ in urllib.parse.parse_qsl(urllib.parse.urlsplit(u1.data).query) if 'xpire' in k]}")
        after = check("证书", "NORMAL", "GET", "/v/honor/certificates?page=1&size=50")
        st = next((x for x in (after.data or {}).get("records", []) if str(x["id"]) == not_ready), {}) if after else {}
        ev.add("证书", "NORMAL", f"证书 {not_ready}", "—", "（下载之后）", Resp(200, 200, st, None, None, b""),
               expect=lambda r: r.data.get("fileReady") is True, note=f"fileReady={st.get('fileReady')} downloadCount={st.get('downloadCount')}")
    if ready:
        check("证书", "NORMAL", "GET", f"/v/honor/certificates/{ready}/file")
        check("证书", "NORMAL", "GET", f"/v/honor/certificates/{ready}/preview")
        check("证书", "GUEST", "GET", f"/v/honor/certificates/{ready}/file", expect=rejected,
              note="别人的证书：与「不存在」同一句话，防枚举")

    # ---- 5 奖惩与申诉 ----
    for p in (1, 2, 3):
        check("奖惩", "NORMAL", "GET", f"/v/honor/reward-punishes?page={p}&size=10",
              expect=lambda r: r.ok and all(k in r.data for k in ("records", "total", "page", "size", "pages")))
    if args.with_appeal and "NORMAL" in tok:
        page2 = api.call("GET", "/v/honor/reward-punishes?page=2&size=10", tok["NORMAL"])
        target = next((x for x in (page2.data or {}).get("records", []) if x.get("appealable")), None)
        if target:
            up = check("申诉", "NORMAL", "POST", "/v/files/appeal-image",
                       multipart=("file", "proof.png", "image/png", PNG))
            url = (up.data or {}).get("url") if up and up.ok else None
            path = f"/v/honor/reward-punishes/{target['id']}/appeal"
            check("申诉", "NORMAL", "POST", path, expect=rejected, body={"reason": "长" * 1025, "imageUrls": [url]},
                  note="1025 字应被拒")
            check("申诉", "NORMAL", "POST", path, expect=rejected, body={"reason": "七张", "imageUrls": [url] * 7},
                  note="7 张应被拒")
            check("申诉", "NORMAL", "POST", path, body={"reason": "申" * 1024, "imageUrls": [url] * 6},
                  note="1024 字 + 6 张")
            check("申诉", "NORMAL", "POST", path, expect=rejected, body={"reason": "再交一次", "imageUrls": [url]},
                  note="重复提交应被拒")
            again = api.call("GET", "/v/honor/reward-punishes?page=2&size=10", tok["NORMAL"])
            st = next((x for x in (again.data or {}).get("records", []) if x["id"] == target["id"]), {})
            ev.add("申诉", "NORMAL", f"奖惩单 {target['id']}", "GET", "/v/honor/reward-punishes?page=2&size=10", again,
                   expect=lambda r: st.get("appealStatus") == 1 and st.get("appealable") is False,
                   note=f"appealStatus={st.get('appealStatus')} appealable={st.get('appealable')} 凭证张数={len(st.get('appealImageUrls') or [])}")

    # ---- 6 其余接口：非空、多页、角色拒绝 ----
    check("投诉", "NORMAL", "GET", "/v/data/complaints/mine?page=1&size=2")
    check("投诉", "NORMAL", "GET", "/v/data/complaints/mine?page=2&size=2")
    for tab in ("hot", "following", "official"):
        check("社区", "NORMAL", "GET", f"/v/social/posts?page=1&size=10&tab={tab}&keyword=")
    check("社区", "NORMAL", "GET", "/v/social/posts?page=2&size=10&tab=hot&keyword=")
    check("社区", "NORMAL", "GET", "/v/social/posts?page=1&size=10&tab=hot&keyword=" + urllib.parse.quote("联调测试"))
    check("考试", "NORMAL", "GET", "/v/organization/exams/current")
    check("考试", "NORMAL", "GET", "/v/organization/exams/attempts?page=1&size=20")
    check("问卷", "NORMAL", "GET", "/v/organization/forms?page=1&size=20")
    if c.get("formId"):
        check("问卷", "NORMAL", "GET", f"/v/organization/forms/{c['formId']}")
    check("相册", "NORMAL", "GET", "/v/activity/albums?keyword=&page=1&size=20")
    check("相册", "GUEST", "GET", "/v/activity/albums?keyword=&page=1&size=20", expect=rejected, note="游客：实名后才能看")
    if c.get("activityId"):
        check("相册", "LEADER", "GET", f"/v/activity/activities/{c['activityId']}/album",
              expect=lambda r: r.ok and r.data.get("uploadable") is True, note=None)
        check("公示", "NORMAL", "GET", f"/v/activity/rosters/{c['activityId']}")
        check("公示", "GUEST", "GET", f"/v/activity/rosters/{c['activityId']}", note="游客看：负责人电话也打 *")
    check("公示", "NORMAL", "GET", "/v/activity/rosters?page=1&size=10")
    check("文件", "NORMAL", "GET", "/v/system/files")
    check("地址", "NORMAL", "GET", "/v/user/addresses")
    check("保险", "NORMAL", "GET", "/v/user/center-contents/insurance")
    check("客服", "NORMAL", "GET", "/v/user/center-contents/customer-service")
    check("提醒偏好", "NORMAL", "GET", "/v/user/notify-preferences")
    check("通知", "NORMAL", "GET", "/v/notifications?page=1&size=10")

    # ---- 受限账号：被挡在大部分接口外，奖惩 / 处置 / 申诉 / 通知仍可达 ----
    check("受限账号", "RESTRICTED", "GET", "/v/user/profile", expect=rejected)
    check("受限账号", "RESTRICTED", "GET", "/v/activity/activities?page=1&size=10", expect=rejected)
    check("受限账号", "RESTRICTED", "GET", "/v/honor/reward-punishes?page=1&size=10")
    check("受限账号", "RESTRICTED", "GET", "/v/honor/sanctions",
          expect=lambda r: r.ok and isinstance(r.data, list) and len(r.data) > 0)
    check("受限账号", "RESTRICTED", "GET", "/v/notifications?page=1&size=10")

    bad = ev.write(args.base, "acceptance-evidence")
    sys.exit(1 if bad else 0)


# ---------------------------------------------------------------- cleanup

def cleanup(api, args):
    m = load_manifest(args.manifest)
    confirm_write(args, "撤掉 seed 建的测试数据")
    ev = Evidence()
    admin = admin_login(api, args)
    tok = volunteer_tokens(api, args)
    c = m.get("created", {})
    vid = {role: v["id"] for role, v in m.get("volunteers", {}).items()}

    def step(item, role, method, path, token, body=None):
        r = api.call(method, path, token, body)
        ev.add(item, role, None, method, path, r, expect=lambda x: x.ok)

    for pid in c.get("postIds", []):
        if "NORMAL" in tok:
            step("社区", "NORMAL", "DELETE", f"/v/social/posts/{pid}", tok["NORMAL"])
    if c.get("activityId"):
        step("活动", "ADMIN", "POST", f"/a/activity/activities/{c['activityId']}/cancel?reason="
             + urllib.parse.quote("联调测试结束"), admin)
    if c.get("formId"):
        step("问卷", "ADMIN", "POST", f"/a/organization/forms/{c['formId']}/close", admin)
    if c.get("examPaperCreated") and c.get("examPaperId"):
        step("考试", "ADMIN", "POST", f"/a/organization/exam-papers/{c['examPaperId']}/close", admin)
    if c.get("restrictTicketId"):
        step("受限账号", "ADMIN", "POST", f"/a/honor/reward-punishes/{c['restrictTicketId']}/lift-sanction", admin,
             {"reason": "联调测试结束"})
    if "LEADER" in vid and str(c.get("leaderManagerFlagBefore")) != "1":
        step("活动", "ADMIN", "PUT", f"/a/organization/volunteers/{vid['LEADER']}/manager-flag", admin, {"flag": 0})
    print("  · 奖惩单、投诉、证书不删：留在专用测试账号上，是验收记录的一部分")
    ev.write(args.base, "acceptance-cleanup")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=("seed", "verify", "cleanup"))
    ap.add_argument("--base", required=True, help="接口根地址，含 /api，例如 http://118.145.69.25/api")
    ap.add_argument("--manifest", default="acceptance-manifest.json")
    ap.add_argument("--dev-login", action="store_true", help="本机开发库：用开发登录代替 HD_TOKEN_*")
    ap.add_argument("--with-appeal", action="store_true", help="verify 时真的提交一次申诉（会用掉一张可申诉的处罚）")
    ap.add_argument("--yes", action="store_true", help="写库前不再询问")
    args = ap.parse_args()
    api = Api(args.base)
    {"seed": seed, "verify": verify, "cleanup": cleanup}[args.action](api, args)


if __name__ == "__main__":
    main()
