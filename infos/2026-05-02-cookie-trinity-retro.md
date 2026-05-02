# 2026-05-02 Cookie 三连修复复盘

## TL;DR

调试一整天的"重置会话后课表/附件挂"症状，最终定位到**三个独立 bug 叠加**。修完后系统首次稳定。

| 病症 | 根因 commit | 一句话总结 |
|---|---|---|
| 重置会话后 refresh 报"会话已过期" | `1ae40d3` | doRefreshCookies 的 entry URL 判断只看 cookie 数量，不看是否真登录 |
| **backend 永远只收到 1 个 cookie，浏览器同请求收 6 个** | `959228d` | **Apache HttpClient 5 默认 cookie store 没禁用，把 host-only cookies 全吞了** |
| `重置会话` 报 2001 "未提供身份标识" | `7697562` | reset 强制要 userId，但 reset 的语义本身就是"我没 userId 了想重来" |

`959228d` 是核心，是几天调试都没找到的真正根因。其他两个是其连锁反应。

## 时间线

### 1. 表象：用户报告"重置会话后课表挂、附件挂"

**长期症状**：用户每次重置会话+重登后，课表打不开、附件下载报错。怀疑过：
- 后端 cookie 池污染
- 学校 logout 后"快速重登窗口"
- 前端清 cookies 没清干净
- AcademicServiceImpl 短路逻辑 stale cookie 干扰

每条都试了，每条都不是根因。

### 2. 用户的关键观察（破局）

> "我在浏览器里面清掉 cookie 是不会出现那么多问题的，它只会要求我用 sms 登录，然后一切正常 —— 仅此而已。"
>
> "但是退出登录、重置会话，我还是要经历这些什么'重建 jwxt SSO 链'狗屎"

这句话排除了所有"学校服务端记忆"假设。**学校行为不是因，差异在 backend 和浏览器之间**。

### 3. 用户提供 4 张浏览器 cookie 截图

显示浏览器手清 cookie 后，访问学校各域看到的真实 cookie 数：
- `auth-sztu-edu-cn-s.webvpn:8118`：6 个
- `home-sztu-edu-cn-s.webvpn:8118`：4 个
- `jwxt-sztu-edu-cn-s.webvpn:8118`：6 个

**而 backend log 里 `🍪 doRefreshCookies 请求后有 1 个 Cookie`**。

### 4. 加 `[ProxySession Δ]` delta log（commit `d802d2b`）

让用户看每次 ProxySession 写入前后 cookie 名字+域的差分。意图：观察 logout / login 时 cookies 怎么变。

### 5. 加 `AuthFlowTracer`（commit `8dd603f`）

`auth.trace.enabled=true` 时把 5 个 auth API 全程每跳落盘到 `infos/runtime-trace/auth-flow/<ts>_<stage>_<userId>/`，包括请求 / 响应 / cookies 状态。

### 6. 修 `doRefreshCookies` entry URL 判断（commit `1ae40d3`）

老逻辑：
```java
if (cookies.isEmpty())  → thdportal_login (服务端重定向)
else                    → gatewayStartURL (/por/ JS 重定向，SmartHttpClient 跑不动)
```

reset+init 后前端剩 1 个 `_idp_authn_lc_key_` cookie，`isEmpty()=false` 触发错误分支 → 落 /por/ 门户 → 误判"会话过期"。改判据为 `存在 TWFID 或 webvpn:SESSION` 才走 gatewayStartURL。

修完后**用户能进登录页且看到 SMS+PASSWORD 双 tab**，但登录还是挂。

### 7. 修 `/session/v1/reset` 不再要 userId（commit `7697562`）

```
[UserStore] 清除后端会话失败 {code: 2001, message: "未提供身份标识"}
```

reset 的语义就是"用户状态乱了想重来"，强制要 userId 反而堵死自救路径。改成无 userId 时后端 no-op 但返成功。

### 8. **真正根因**：SmartHttpClient 的 Apache cookie store bug（commit `959228d`）

继续追"为啥 1 个 cookie 而不是 6 个"。最终在 `SmartHttpClientImpl.java:92`：

```java
this.httpClient = HttpClients.custom()
        .setConnectionManager(connManager)
        .disableRedirectHandling()  
        // ← 缺少 .disableCookieManagement()
        .setDefaultRequestConfig(...)
```

**Apache HttpClient 5 默认带 CookieStore**，按 RFC 6265 严格规则**消化 set-cookie 头**。host-only cookies（无 `Domain=` 或 `Domain=` 不带前导点的）—— 学校的 `SESSION @ auth-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118` 就是这种 —— 被 Apache 吞了，**不再出现在 `response.getHeaders("Set-Cookie")`** 里。

我们的 `SmartSessionImpl.parseAndAddCookies` 解析的是这个被 Apache 过滤后的列表。结果：domain cookies (`_idp_authn_lc_key_-_auth.sztu.edu.cn` Domain=.webvpn.sztu.edu.cn）留下；所有 host-only 的 SESSION / JSESSIONID / TWFID 全丢。

浏览器为啥没事？**浏览器自己就是 cookie jar，不存在"二次过滤"**。

修复一行：加 `.disableCookieManagement()`。所有 set-cookie 头原样到 SmartSession，按我们自己的逻辑（domain match / path match / host-only 都正确处理）。

### 9. 启用 cookie 回写 + diff log（commit `7fc27af`）

修完后 cookies 真的开始变化（每 60s 学校 SESSION 轮换），日志看到 `[syncCookies] 8→9 changed=true`。但写回链路之前因 race 被禁用了。

3-tier 硬规则（CLAUDE.md §6.0）保证安全：
- WS 在线
- schoolLoggedIn=true
- 有变化

全过才写回 + 推 WS。每次 log 出 added/removed/changed 三个 cookie 列表。

## 可写到论文/CLAUDE.md 的经验

### 教训 1：自研 HTTP 客户端必须显式禁用框架内置 cookie 处理

任何"自己管 cookie jar"的设计都要 explicit `.disableCookieManagement()`。框架内置 cookie store 默认开 + RFC 6265 严格过滤，对**学校 IDP 这种 host-only cookie 重度依赖**的场景**致命**。

CLAUDE.md 里 SmartHttpClient 的章节应该加：
> `.disableCookieManagement()` 是必备配置。Apache HttpClient 5 默认 CookieStore 会过滤 host-only cookies，吞掉学校 IDP 的 SESSION / JSESSIONID。如果没禁用，所有"自己管 cookies"的逻辑都是假的——你拿到的 cookies 已经被框架过滤过。

### 教训 2：症状对症 vs 找根因

我前几个 session 一直在"症状对症"：
- 看到 cookie 不全 → 怀疑 logout 没清干净
- 看到 reset 失败 → 怀疑学校"快速重登"
- 看到课表挂 → 怀疑 jwxt SSO 链断

每个对症的修法都治标不治本。直到用户给出"浏览器对照实证"，才发现**是 backend 收 cookie 那一层就出问题**，所有上层修补都无意义。

**通用方法**：黑盒症状对症复杂时，**找一个"我知道是对的"参照系**（用户的浏览器观察），对照 backend 行为，差异点就是根因。

### 教训 3：Trace 模式真的有用，但只在用户绝望时才会想做

`acdm.trace.enabled` 模式存在但很少用。这次把它通用化到 auth 全流程（`auth.trace.enabled`）才让定位有了方向。每个核心模块都该有这种"打开开关全程落盘"模式，关键时刻能救命。

### 教训 4：用户怒火往往是关键信号

用户那句"草泥马的缓存，哪里来的缓存？为什么会有缓存？" + 4 张截图 + "做规划，少找理由" 实际给了我**全部破案信息**。

之前几个 session 我都在猜。直到被怼 + 看到截图证据，才换成"对照浏览器+backend"调查思路。

## 最终架构状态

修复后的 cookie 流：

```
学校 IDP 发 set-cookie (含 host-only)
  ↓
Apache HttpClient 5 (NOT consuming, .disableCookieManagement())
  ↓ response.getHeaders("Set-Cookie") 全集
SmartSessionImpl.parseAndAddCookies
  ↓ 按 domain/path 严格 RFC 6265 + host-only 正确处理
SmartSession.cookieStore Map<domain, Map<name, SmartCookie>>
  ↓
返回前端 (X-Set-Cookies header) + 写 Redis ProxySession (有3-tier 守卫)
```

每个写入点都有 [ProxySession Δ] / [syncCookies] log 留痕。

## 修复后的 commits（按时间）

| commit | 内容 |
|---|---|
| `d802d2b` | feat: ProxySession Δ delta log |
| `8dd603f` | feat: AuthFlowTracer 全流程落盘 |
| `1ae40d3` | fix: doRefreshCookies entry URL 判据 |
| `7697562` | fix: /session/v1/reset 容忍无 userId |
| **`959228d`** | **fix: SmartHttp disableCookieManagement**（核心根因）|
| `7fc27af` | feat: 启用 cookie 回写 + diff log |

## 后续

- [x] 用户报告"成功了，目前重置会话比较稳定"
- [ ] 论文 4.7 节加这个根因案例（"工程师陷阱：框架内置 cookie store 与自研 cookie jar 的兼容性"）
- [ ] 监控 `[syncCookies]` log 频率，看回写次数是否在合理范围
- [ ] 监控 `[ProxySession Δ]` 看每次登录 cookie 集合是否完整
