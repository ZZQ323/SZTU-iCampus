# 登录-登出-重登 全流程 trace 实操指南

## 目的

用 backend 落盘的 trace + 浏览器实测对照（你已经做过的 4 张截图）找出
**为什么浏览器清 cookie 后正常，小程序登出后重登异常**的精确分歧点。

## 操作步骤

### 1. 打开 trace 模式

`main/src/main/resources/application-dev.yml` 加（或确认存在）：

```yaml
auth:
  trace:
    enabled: true
    dir: infos/runtime-trace/auth-flow
```

### 2. 重启后端

```cmd
gradlew.bat :main:bootRun
```

启动 log 里搜 `[AUTH-TRACE]` 应没输出（只有真触发 auth 操作时才输出）。

### 3. 完整复现流程（**严格按顺序**）

| 序号 | 操作 | trace 目录会出现 |
|---|---|---|
| 0 | 微信开发者工具 → 清缓存 → 全部清 | — |
| 1 | 重启微信开发者工具 | — |
| 2 | 打开小程序 → 触发 init | `<ts>_initSession_anon` |
| 3 | 用 SMS 登录 → 输入学号 | `<ts>_getSms_<学号>` |
| 4 | 输入收到的验证码 → login | `<ts>_login-SMS_<学号>` |
| 5 | 进入"我"页面 → "退出登录" | `<ts>_logout_<学号>` |
| 6 | 重新登录（init→sms→login） | 新的 `_initSession_` / `_getSms_` / `_login-SMS_` 三个目录 |

### 4. 提交 trace

```cmd
git add infos/runtime-trace/auth-flow/
git commit -m "trace: full login-logout-relogin"
git push
```

## 期望 vs 实际判断矩阵

每个 trace 目录里看 `_summary.txt` + `hop-NNN_*_cookies.json`。

### 第一次登录链 (步骤 2 → 3 → 4)

| trace dir | hop | 期望 cookies (count + 关键名字) | 判断 |
|---|---|---|---|
| `_initSession_anon` | 1 doRefresh-pre | 0（空 session） | ✓ 干净起点 |
|  | 2 doRefresh-gateway | 1-3：`_idp_authn_lc_key_@auth`, `SESSION@auth`, `x@auth` | ✓ 学校发预登录 |
| `_getSms_<id>` | 1 sms-pre | 0 | ✓ getSms 强制空 session |
|  | 2 sms-step1-thdportal | 3：`_idp_authn_lc_key_@auth`, `SESSION@auth`, `x@auth` | ✓ |
|  | 3 sms-step2-postAjax | 同上（不增） | ✓ 短信发出 |
| `_login-SMS_<id>` | aux | 前端传来 cookies = 上面 sms 的 3 个 | ✓ |
|  | 1 login-pre-loaded | 3 | ✓ 加载前端 cookies |
|  | 2 ajax-verify | 3-4（可能加 SESSION 轮换） | ✓ |
|  | 3 form-submit | **5-6**：`+ TWFID@webvpn`, `+ AD_SESSION_FLAG@auth`, `+ JSESSIONID@auth` | ✓ **TWFID 出现 = WebVPN 真登录成功** |
|  | 4 fetch-bmportal | 同上 + `JSESSIONID@home` | ✓ home portal session |

**判断 1**：第一次登录后 hop-3 cookies 数应该 ≥ 5 且包含 `TWFID`。否则说明学校第一次登录就出问题（应该不会，浏览器实测能登）。

### 登出链 (步骤 5)

| trace dir | hop | 期望 | 判断 |
|---|---|---|---|
| `_logout_<id>` | aux | 前端传来的 cookies = 完整登录态 5+ 个 | ✓ |
|  | 1 logout-pre | 同上 | ✓ |
|  | 2 logout-call | **学校 set-cookie 头操作**：通常 `SESSION` 被轮换、`TWFID` 可能保留、`AD_SESSION_FLAG` 仍在 | 看实际 |

**判断 2**：登出后 SmartSession cookies 减少了哪些？
- 如果 `TWFID` 还在 → 学校 logout 没废 webvpn 网关 cookie，**这是关键**，下次重登会被识别
- 如果 `TWFID` 没了 → logout 干净
- 如果 `JSESSIONID@home` 还在 → home portal session 没断

### 第二次登录链 (步骤 6)

| trace dir | hop | 期望 vs 第一次 | 判断 |
|---|---|---|---|
| `_initSession_anon` | 1 pre | 0 | ✓ 应该跟第一次一样 0 |
|  | 2 gateway | 跟第一次比对：cookies 名字+域是否一致？ | **判断 3** |
| `_getSms_<id>` | 1 pre | 0 | ✓ |
|  | 2 thdportal | 跟第一次比对 | **判断 4** |
|  | 3 postAjax | 同上 | ✓ |
| `_login-SMS_<id>` | aux | 前端传来 cookies | 应该跟第一次几乎一样 |
|  | 3 form-submit | **关键**：cookies 数 vs 第一次 hop-3 | **判断 5** |

**判断 3**：第二次 init `gateway` cookies vs 第一次 init `gateway` cookies。如果**不同**，说明前端 cookies 不是空（reset 没清干净）。**这是首要怀疑**。

**判断 4**：getSms 用空 session（强制 newSession()），所以 hop-2 应该跟第一次完全一样。如果**不同**，说明 SmartHttpClient 全局有状态（可能 cookie pool 干扰）。

**判断 5**：第二次登录 form-submit 后 cookies 数 vs 第一次：
- 一样多 + 一样的名字 → **登录流程没问题**，问题在 acdm/jwxt 单独路径
- 比第一次少（缺 TWFID 或 AD_SESSION_FLAG）→ **学校进入"已识别"快速路径**，根因在第二次登录请求
- 比第一次多 → 异常情况

## 三种可能的根因 → 对应判断结果

| 根因 | 判断 3 | 判断 4 | 判断 5 |
|---|---|---|---|
| **A. 前端 cookies 没清干净** | ❌ 不一致 | ❌ 不一致（很奇怪因为 getSms 用空 session）| ✓ 跟第一次一样多 |
| **B. SmartHttpClient 全局有状态** | ❌ 不一致 | ❌ 不一致 | 看情况 |
| **C. 学校"快速重登"识别（IP+UA）** | ✓ 一致（都是空起点）| ✓ 一致 | ❌ 比第一次少 |
| **D. logout 没真清学校状态** | ✓ 一致 | ✓ 一致 | ❌ 比第一次少 |

C 和 D 的区别是否能干预 —— C 是学校行为不可改；D 是后端 logout 调法的问题。

## 如果 trace 还看不出来

启用 Plan B 单元测试 / Plan C playwright 浏览器对照。但 90% 概率走到这步前已经定位。
