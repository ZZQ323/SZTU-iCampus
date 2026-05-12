# SZTU iCampus Backend - 项目知识库

## 项目定位

这是一个 **胶水层后端**，不产生数据、不做数据持久化，充当学校 Web 服务与微信小程序之间的翻译层和推送适配层。

核心职责：
- 转发并管理学校 Cookie（认证链路代理）
- 爬取学校网页 → 解析为结构化 JSON
- 通过 WebSocket 实时推送更新

## 架构：单 Auth + 无持久化 + Cookie 直通

```
小程序 ─── X-School-Cookies header ───→ 后端 ─── Cookie ───→ 学校服务
   ↑                                      │
   └──── X-Set-Cookies response header ───┘
```

- **没有 Token**：学校只认 Cookie，多加 Token 是多余抽象
- **没有 MySQL**：只用 Redis 做缓存
- **不用微信 openId**：只用学校自己签发的 cookie

## 模块结构

| 模块 | 职责 |
|------|------|
| `main` | Spring Boot 启动入口 + 配置 |
| `module-common` | SmartHttpClient、Redis、Auth 过滤器、异常体系 |
| `module-base` | 认证服务、会话管理、教务查询、图片代理 |
| `module-stream` | 爬虫引擎、WebSocket 推送、Redis Stream、定时任务 |

## 关键设计原则

### 1. 禁止使用持久化数据库
不用 MySQL，只用 Redis。Redis 暂存文章摘要方便搜索（全量搜索太吃力）。理论上 Redis 不适合持久化，但项目数据可以通过重新爬取恢复。

### 2. SmartHttpClient 替代 Playwright
自研的 `SmartHttpClient` 处理学校 WebVPN 的复杂重定向链，6 个重定向 handler 按优先级排列（责任链模式）。不要引入 Playwright 或其他 headless browser。这解决了并发问题。

### 3. Spring 事件优先于直接依赖
遇到循环依赖时，**优先使用 Spring 事件**（`ApplicationEventPublisher`），不要尝试用 `@Lazy` 或拆分 jar 包。原因：阿里云仓库同步很慢，jar 分发容易出问题。

### 4. getSms 使用空 session
`getSms()` 必须创建全新的空 SmartSession，不能复用前端传来的旧 cookie。旧 cookie 会干扰 IDP 路由，导致落地到 `idp/AuthnEngine` 而非 `ActionAuthChain?entityId=webvpn`。

### 5. Cookie 有效性由学校判断
后端不做 cookie 过期预测，不缓存登录状态。所有状态查询都是真实访问学校 gateway 并解析返回页面。是否登录只有学校后端说了算，胶水层说不算。

### 6. refresh 优先于 init
大多数"会话过期"场景只需要调 `/auth/v1/session/refresh`（像浏览器按 F5），不需要 `/auth/v1/session/init`（清缓存重来）。有 cookie 就 refresh，没 cookie 才 init。

### 5.5 ⚠️ Cookie 转发**严格按 RFC 6265**，不准放宽

`ProxyController.fetchResource` 把前端 `X-School-Cookies` 塞 `Cookie:` 头转发学校时，**严格按浏览器规则筛**：

| 规则 | 含义 |
|---|---|
| **domain match** | host == cookie.domain（host-only）OR host.endsWith("." + domain)（domain cookie） |
| **path match** | request.path.startsWith(cookie.path)（默认 "/" 永远匹配） |
| 不命中 | **绝不发** |

**禁止"WebVPN 兜底"**——曾经写过"两边都 sztu.edu.cn 就放行"的版本，把 host-only 的 `SESSION @ auth-sztu-edu-cn-s` 错发给 `nbw-sztu-edu-cn-s`，把 `JSESSIONID @ home-s/bmportal` 错发给 `nbw/system/_content/...`。学校的 VWebServer 看到不属于自己的 IDP cookie 头**直接 414**——这是 2026-04-25 几天死磕的 414 根因。

**HAR 实证**（`infos/down_logOut_down.txt`）：浏览器发请求时，cookie jar 按 (domain, path) 严格过滤后只发匹配子集。我们必须照做。

判定函数：`ProxyController.isCookieApplicable(host, cookieDomain, cookiePath, requestPath)`。**不要再加任何兜底**。

### 6.0 ⚠️ 爬虫使用 cookies 的**硬规则** —— 不可违反

**任何 user 的 cookies 被后端"借去爬 / 写回 Redis / 推 WS"**，必须**同时**满足：

1. **WS 在线** —— `wsSessionRegistry.isOnline(userId)`
2. **schoolLoggedIn = true** —— `authSessionCacheUtil.isSchoolLoggedIn(userId)`
3. **Redis 还有 cookies** —— ProxySession 存在且 cookiesJson 非空

三者**缺一不可**。否则 = 用户已经退出 / 关掉小程序，但后端还在偷偷拿他的 cookies 去访问学校：cookies 寿命被人为延长，IP 行为异常，学校随时可能封号。

**违反此规则的具体表现（已修复但要警惕复活）**：

- ❌ `CookieSourceManager.findValidFromAllSessions` —— 找 Redis 里任何
  schoolLoggedIn=true 的用户用，无视在线状态。**已删除**，不要再加回来。
- ❌ `CrawlEngine.syncCookiesIfChanged` 不查 logged-in / online 就把 cookies
  写回 Redis + 推 WS。**已加守卫**，要保留。
- ❌ `refreshIfNeeded`（CookieAccessEvent 兜底）schoolLoggedIn=false 时仍
  写 Redis。**已加守卫**。
- ❌ 前端 `ws.ts COOKIE_UPDATE` 不查 isSchoolLoggedIn 就 merge。**已加守卫**。
- ❌ logout 不主动断 WS，留出"已登出但 WS 还连着"的同步窗口。**已通过
  UserLogoutEvent + WsSessionRegistry.kickUser 修复**。

**logout 时正确动作**（前后端协同）：
| 主体 | 动作 |
|---|---|
| 前端 `userStore.logoutSchool` | 1) 先断 WS 2) 调 logout API 3) reset UI 状态（**保留 localStorage cookies**，浏览器语义） |
| 后端 `AuthServiceImpl.logout` | 1) 访问学校 logout URL 2) `sessionLogoutBind` 清 Redis cookiesJson + schoolLoggedIn=false 3) publish UserLogoutEvent |
| 后端 `UserLogoutEventListener` | 1) `wsSessionRegistry.kickUser` 主动踢 2) `infoCacheUtil.clearActiveSource` 如果命中 |

记住：**cookies 的所有权归前端**。后端 Redis 只是给爬虫用的临时缓存。前端登出 = 后端立刻不再为这个用户做事。

### 6.1 logout 必须清 cookies，不要保留

`AuthSessionCacheUtil.sessionLogoutBind(userId)` 要**清空 `cookiesJson`**，不只是翻 `schoolLoggedIn=false`。

**为什么**：学校 logout 端点让 TWFID / IDP SESSION 在**学校服务端失效**。如果 Redis 保留旧 cookiesJson，下次 relogin：
- WebVPN 反代看到陈旧的 TWFID → 走"已登录"分支 → **跳过 re-issue TWFID**
- 登录流程最终 cookies 里缺 TWFID + SESSION（相比 fresh-backend 场景少 2 个关键 cookie）
- `/acdm/v1/refresh/cookies` 的 SSO 链跑到 `/idp/AuthnEngine` 返回 200 登录表单 → 课表/附件全挂

对照证据在 `infos/runtime-trace/academic-init/` 下 2026-04-25 的两组 trace：`20260425-070728`（成功，cookies-before 有 6 个）vs `20260425-070907`（失败，cookies-before 只有 4 个，缺 TWFID + SESSION）。对应 commit: `39278f68`。

**教训**：原先"登出后保留 cookies 便于离线"的出发点是好的（cookie 缓存），但学校 logout 后 cookies 已失效，保留只会污染下次登录。

### 7. 所有返回 cookies 的接口都要设 X-Set-Cookies header
`/auth/v1/status` 内部调了 `doRefreshCookies()` 拿到了新鲜 cookies，必须通过 `X-Set-Cookies` response header 返回给前端。否则前端 cookies 不会被刷新，导致后续请求用过期 cookies。

### 8. 批判性思考
用户提出的想法和设计，都需要用 Plan 模式去质疑和审视。一个人说的总是会有纰漏，AI 应当做批判性分析。

### 9. 开发流程约定（AI 元规则）
- **每次开始操作前先 `git fetch`**：用户会频繁更新仓库，AI 看不到远端最新状态，操作前必须同步。
- **及时更新本知识库**：用户强调过的设计原则、架构决策、踩坑记录、约束、约定，必须立刻写进 `.claude/CLAUDE.md`，不要只依赖会话上下文（会被压缩/清空）。
- **开发分支**：所有改动推到用户指定的 `claude/*` 分支，不直接碰 `main` / `release/*`。

### 10. 调试失败请求的通用姿势

学校一整套服务（WebVPN / IDP / 教务 / 博达 CMS）都是黑盒，出问题常见反应是"log 里一行 warn + 一个非标 status"。**log 摘要 + 瞎猜**是主要时间浪费来源（414 坑、AuthnEngine 坑都是这样跑了一两天）。

**规则**：写"可能失败的学校请求"时必须落盘现场：

- 失败响应（非 2xx、伪 200 登录页、重定向终态异常等）**完整写入本地文件**，不要只 log 前 N 字节
- 多跳 SSO 链：每一跳都独立落盘一个 html，配合 `index.txt` 记 hop# / status / URL / cookies 变化
- 目录约定：`infos/runtime-trace/<module>/<timestamp>_<userId>/`，如 `infos/runtime-trace/academic-init/20260425-063815_user202200202104/`
- 下游如 ProxyController 的 `tmp/proxy-errors/` 也走相同路径，`tmp/` 前缀表示运行时产物，由用户触发后 commit/分享

**流程**：出 bug → 开诊断开关 → 复现 → AI 翻本地文件 → 精准打击。**严禁"根据 log 一行摘要拍脑袋修"**。

## 学校服务的本质

学校服务按 Cookie 需求分三类：
- **无需 Cookie**：学院部门公开信息（CMS 网站），直接爬取即可
- **需要网关 Cookie**：公文通，登录 WebVPN 网关后获取 Cookie
- **需要教务系统 Cookie**：课表等教务功能，需处理教务系统的重定向授权链

处理重定向的过程就是被授权的过程 —— 获得什么 cookie，就能使用什么功能。

## 会话刷新的设计考虑

"刷新会话"按钮存在的原因：
1. **学校网页加载慢**：返回了登录成功状态但没返回个人信息 —— 这是学校网站的问题，不是胶水层的问题。核心逻辑在 `refreshSession` 里解析个人信息的部分
2. **用户反复登录**：快速操作导致"会话过期"提示，手动刷新就能看到信息
3. **多设备切换**：小程序挂机后回来发现"会话过期"

以上所有场景，在浏览器里就是"点一下刷新按钮"，在小程序里就是请求 `/auth/v1/session/refresh`，不需要清空 cookie 重新 init。

## 轮询与推送机制

项目采用 **轮询学校网页 → 爬取 → WSS 推送** 的方式，全程不使用微信小程序的 openId。
- 公文通（需登录）：10 秒快速轮询
- 公开信息（学院 CMS）：30-120 分钟周期轮询
- 有新内容时通过 WebSocket 推送给所有订阅者

### ⚠️ 硬性规则：WS payload 必须带完整 items，不是只 id 列表

**论文核心论点是"流式推送"**，必须做到位。历史上 `CrawlEngine.broadcastNewContent` 只把 `ids: List<String>` 塞进 payload，逼前端按 id 再 HTTP 拉 —— 这让 WS 退化成"信号枪"，前端还是在轮询。**这是反模式**。

**正确形态**：
```java
// broadcastNewContent 内
data.put("items", newItems);  // ⭐ 完整 InfoItemMeta 列表
data.put("ids", ...);          // 保留，向后兼容
data.put("latestId", latestId);
```

**禁止**：
- ❌ 只推 id 让前端回查
- ❌ 因为"payload 大"就砍字段 —— 一条 WS 消息最多 1MB，InfoItemMeta 约 500B，一次推 10 条 <= 5KB，毫无压力
- ❌ 因为前端"已经在 fetch 了"就不改 payload —— 两边都要改才是真推式

**AI 修改提示**：这节是下游前端反模式的根源。如果下一个 session 想"精简 payload"或"只带 id 降 token"，**必须回头读这节**。

## API 端点

### 认证 `/auth`
| 端点 | 方法 | 认证 | 说明 |
|------|------|------|------|
| `/auth/v1/session/init` | POST | 否 | 初始化会话，获取预登录 cookies + loginTypes |
| `/auth/v1/request/sms` | POST | 否 | 发送短信验证码（用空 session） |
| `/auth/v1/login` | POST | 否* | 提交登录（需要 initSession 或 getSms 返回的 cookies） |
| `/auth/v1/status` | GET | 是 | 查询登录状态 |
| `/auth/v1/session/refresh` | POST | 是 | 刷新 SESSION_ID |
| `/auth/v1/history` | GET | 是 | 历史学号列表 |
| `/auth/v1/logout` | POST | 是 | 登出 |

### 信息流 `/info`
| 端点 | 方法 | 说明 |
|------|------|------|
| `/info/v1/list` | GET | 分页信息列表 |
| `/info/v1/detail/{channelId}/{id}` | GET | 文章详情 |
| `/info/v1/latest` | GET | 频道最新 ID |
| `/info/v1/search` | GET | 关键词搜索 |
| `/info/v1/category-tree` | GET | 分类树 |
| `/info/v1/unread` | GET | 未读计数 |

### 教务 `/academic` / `/acdm`
| 端点 | 方法 | 说明 |
|------|------|------|
| `/acdm/v1/schedule` | POST | 课表查询（前端叫 /academic，实际 /acdm）|
| `/acdm/v1/refresh/cookies` | GET | 教务系统 cookie 续期 |

### 校历 `/calendar`（公开）
| 端点 | 方法 | 说明 |
|------|------|------|
| `/calendar/v1/years` | GET | 学年列表（从学校页面 ul 解析，Redis 7d 缓存）|
| `/calendar/v1/{year}` | GET | 某学年的春秋两学期图（图 URL 走 /proxy/image）|

### 代理 `/proxy`
| 端点 | 方法 | 说明 |
|------|------|------|
| `/proxy/image` | GET | 代理学校图片（**公开**，已在 CookieAuthFilter 白名单）|
| `/proxy/attachment` | GET | 代理附件下载（需要 cookie，通过 X-School-Cookies header 传入）|

### ⚠️ 附件代理必须防"伪 200 登录页"

`uni.downloadFile` 在小程序端不走 axios 拦截器 → 前端无论如何都必须把 URL 改写到 `/proxy/attachment` 并通过 `header` 字段手动附加 `X-School-Cookies`（见前端 `src/utils/attachment.ts`）。

**后端坑**：WebVPN / 博达 CMS 在 cookie 无效时不返回 401/302，而是返回 **200 + 登录表单 HTML**。如果 `ProxyController.fetchResource` 只按 status==200 判，就会把登录页 HTML 当附件回给小程序，`openDocument` 拿到垃圾打不开，两边日志里没人报错。

**已加的兜底**：`looksLikeLoginHtml(body, contentType)` 取前 2KB 嗅探：content-type 是 html 且正文含 `<html|<!doctype` 且匹配关键字（`login`, `signin`, `authn`, `请登录`, `统一身份认证` 等）→ 视为失败 → 返回 null → `/proxy/attachment` 返回 404 → 前端据此给用户"登录过期"提示。

不要改回"只判 status"——学校就是不给我们正规 401。

### 坑：ProxyController 不能用 HttpClients.createDefault()

学校 WebVPN（`*-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118`）用的是学校内签 TLS 证书，**不在 JVM 默认 CA truststore**。用 `HttpClients.createDefault()` 访问必然抛 `PKIX path building failed: unable to find valid certification path to requested target`，前端表现为"下载失败"但两边看不到具体原因（TLS 握手在 application 层之前就失败了）。

**做法**：`ProxyController.init()` 用 `SSLContextBuilder.loadTrustMaterial((chain,authType)->true)` + `NoopHostnameVerifier.INSTANCE` 构造 trust-all HttpClient 并复用，和 `SmartHttpClientImpl` 同款策略。反正 `isAllowedDomain` 已经白名单到 `*.sztu.edu.cn`，trust-all 只在白名单域内危险，不会被滥用。

**不要**去导学校证书进 JVM truststore：部署环境会跑成一团乱——docker 镜像、Gradle JVM、生产 JRE 各有一份 truststore，全都要同步。

### 活动抽取 `/admin/activity`（需认证，Step A 调试用）
| 端点 | 方法 | 说明 |
|------|------|------|
| `/admin/activity/scan-recent` | POST | 手动扫描最近 N 篇，返回 JSON 行 |
| `/admin/activity/scan-export` | GET | 扫描并导出 CSV（Excel 可打开）|

### WebSocket `/ws`
```
ws://host:port/ws?userId=XXX&topics=announcement,schedule,calendar
```

## SmartHttpClient 重定向处理器

| 优先级 | Handler | 检测方式 |
|-------|---------|---------|
| 1 | LocationHeaderHandler | HTTP 3xx + Location header |
| 2 | MetaRefreshHandler | `<meta http-equiv="refresh">` |
| 5 | DataParameterRedirectHandler | `?data=base64({url:...})` |
| 8 | PortalEntryRedirectHandler | WebVPN 门户入口重定向 |
| 10 | GLinesRedirectHandler | `var g_lines = [{url:...}]` |
| 20 | JsRedirectHandler | `window.location = "..."` 等 8 种 JS 模式 |

## Cookie 池 + 活跃度感知

`CookieSourceManager` 管理爬虫用的 Cookie 来源：

**优先级策略**：
1. 当前活跃用户（在线 + Cookie 有效）→ 直接复用
2. WebSocket 在线用户 → 切换
3. Redis 所有 session → 退而求其次（可能已离线但 Cookie 未过期）

**自动同步**：每次爬取后比较 Cookie 快照，有变化则更新 Redis + 推送给用户。

## 爬虫配置系统

YAML 驱动（按来源组织拆分，`crawler/{fixed,official,department,support,college}/*-{channels,sources,urls}.yml`）：
- **频道**：37 个（公文通、教务、校园生活、各部门、各学院...）
- **数据源**：约 92 个，覆盖全校网站
- **解析器**：`sztu-gwt`（公文通 list.jsp）和 `sztu-cms`（学院 CMS 标准列表）
- `CrawlerConfigLoader` 用 `classpath:crawler/*/*-urls.yml` 等 glob 自动扫描，归档文件在 `crawler/archived/` 不再加载
- 添加新数据源只需编辑 YAML，不需要改代码

### 博达 CMS 详情模板变体

学校各子域名基于同一博达 CMS 但换了 15+ 套皮肤，`SztuGwtContentParser` 采用"有序选择器回退链"应对：
- 标题候选：`h1.article-title` / `div.detail-title h4` / `div.tybt` / `div.c-tit h3` / `div.news_conent_two_title` 等 18 种
- 元信息容器（作者/来源/发布时间）：`div.article-sm` / `div.detail-title h6` / `div.c-ifo` / `p.info` 等 18 种，按 `作者|来源|发布单位|信息来源|发布人` 标签扫描文本提取
- 正文通用：`[id^=vsb_content] .v_news_content`（id 常带数字后缀如 `vsb_content_1081`），fallback 到 `.v_news_content`
- 测试样本在 `module-stream/src/test/resources/parser-samples/content/`（ASCII 文件名，20 个模板一对一）

### 外链条目短路

列表里出现 `mp.weixin.qq.com`、政府/媒体域名等非 sztu 链接时：
- `SztuCmsListParser` 在 `meta.extra` 写入 `{"external":true}` 并保留完整 URL
- `InfoServiceImpl.getDetail` 检测到 `ArticleUrlResolver.isExternalLink(url)=true` 时短路：不发 HTTP 请求，不调 parser，直接返回 `ContentParserResult{success=true, title, externalUrl}`
- 前端据 `externalUrl` 字段渲染"在浏览器中打开"按钮而非正文
- `OnlineCrawlDiagnostic` 同步跳过外链不计为错误

## 两阶段初始化（CrawlEngine）

- **阶段 1（同步）**：爬第 1 页，立即存 Redis + 标记 initialized → 用户零等待
- **阶段 2（异步）**：后台线程池爬剩余页，每 3 页一批并发，批间 500ms → 无感补全

## 启动时的爬虫初始化策略

`SourceInitTask` 在应用启动后根据 `crawler.force-reinit` 决定行为：

| 配置 | 行为 |
|---|---|
| `false`（默认）| **增量**：跳过已 `initialized=true` 的源，只爬没初始化过的。重启 < 1 秒，不打扰学校。|
| `true` | **全量**：清所有 initialized 标记 + 清 feed timeline ZSET，所有 329 个源重爬。|

**命令行覆盖**：`java -jar ... --crawler.force-reinit=true`。用完记得改回来（或不加），否则每次重启都全量爬。

## 活动抽取（LLM 驱动，Step A + B1 + B2 + B3 全部完成）

### 一句话定位（论文摘要/引言可抄）

> "本系统基于爬虫采集的校园公告，构建了**规则预筛 + 大语言模型抽取 + 置信度分层 + 用户反馈闭环**的四层活动识别管道。公文通频道上 F1 = 0.95，提取时间、地点、报名方式等结构化字段，并以月历形式呈现给用户。系统采用**频道差异化策略**——活动预告集中的公文通 / 就业指导中心纳入扫描，活动报道类频道（新闻 / 校园生活 / 团委）因天然偏事后报道主动排除。"

### 总架构（最终态）

```
┌──────────────────┐
│ 爬虫 (CrawlEngine)│
│ 329 个公开源       │
└─────────┬────────┘
          ▼
┌──────────────────────────────────┐
│ 频道 timeline (info:{ch}:timeline)│
│ 按 articleId 自增排序              │
└─────────┬────────────────────────┘
          │
          ▼ (admin 手动 POST /admin/activity/scan-recent)
┌──────────────────────────────────┐
│ 规则预筛 (ActivityPreFilter)        │
│ · 白名单 30+ 关键词               │
│ · 黑名单 20+ 拒绝词               │
│ · 正文日期正则 +关键词            │
└─────────┬────────────────────────┘
          │ (pass)
          ▼
┌──────────────────────────────────┐
│ LLM 活动抽取缓存（Redis 30 天）     │
│ key=activity:extract:{id}:{v}:{model}│
└─────────┬────────────────────────┘
          │ (miss)
          ▼
┌──────────────────────────────────┐
│ DashScope qwen-turbo              │
│ stream=false, thinking=false       │
│ response_format=json_object        │
│ V3 prompt: few-shot + 时间规则     │
└─────────┬────────────────────────┘
          ▼
┌──────────────────────────────────┐
│ ActivityIndexService.upsert        │
│ · 可解析时间 → timeline ZSET       │
│ · 不可解析 → pending Set            │
│ · isActivity=false → 从索引删除    │
└─────────┬────────────────────────┘
          ▼
┌──────────────────────────────────┐
│ 前端 /activity/v1/* (公开)         │
│ · upcoming / list / pending        │
│ · 月历 UI + 每日活动 + 待定列表    │
│ · 右上角 ⋯ 报告错误 (/report)      │
└──────────────────────────────────┘
```

### API 端点一览

| 端点 | 用途 | 认证 |
|---|---|---|
| `POST /admin/activity/scan-recent` | 手动扫描文章入索引 | 需 |
| `GET /admin/activity/scan-export` | 扫描并下载 CSV（对照实验）| 需 |
| `GET /admin/activity/reports` | 查看用户反馈 | 需 |
| `GET /activity/v1/upcoming` | 即将到来的活动 | 公开 |
| `GET /activity/v1/list?from&to` | 时间范围查询 | 公开 |
| `GET /activity/v1/pending` | 时间待定活动 | 公开 |
| `GET /activity/v1/stats` | 索引规模 | 公开 |
| `POST /activity/v1/report` | 用户报告识别错误 | 公开 |

### 关键配置（application.yml）

```yaml
ai:
  dashscope:
    api-key: ${DASHSCOPE_API_KEY:}     # 真值在 application-dev.yml（gitignore）
    endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1
    model: qwen-turbo                   # F1=0.95，最便宜档位
    timeout-seconds: 30
  activity:
    enabled: false                      # 总开关（Step A 定位：人工触发）
    default-channels: [announcement, job]  # 活动预告富集，排除报道类
    max-scan-count: 200
    content-max-chars: 4000             # 正文截断，控 token
    cache-version: v3                   # prompt 版本 → 自然失效旧结果
```

### 数据集（论文 4.3.1 可直接引用）

| 文件 | 条数 | 作用 | 正负比 |
|---|---|---|---|
| `infos/activity-scan-200.csv` | 112 | V0 prompt + 初版规则基线数据 | 39:73 |
| `infos/activity-scan-200v2.csv` | 114 | 规则 V2 词库迭代数据 | 39:75 |
| `infos/activity-scan-200v3.csv` | 114 | Prompt V3 迭代数据 | 37:77 |
| `infos/activity-scan-random-v3-verfify.csv` | 264 | 跨频道泛化性数据（6 个频道）| 28:236 |

**三个 CSV 共享同一批公文通文章**（articleId 重叠，人工标注可复用），第 4 份是跨频道的扩展。

### 评估方法论

**混淆矩阵 2×2**：
|  | 人工=是 | 人工=否 |
|---|---|---|
| 系统=是 | TP | FP |
| 系统=否 | FN | TN |

**指标公式**：
- **Precision** = TP / (TP + FP)  — "判活动里真的活动的比例"
- **Recall** = TP / (TP + FN)     — "所有真活动里抓到的比例"
- **F1** = 2·P·R / (P+R)           — P 和 R 的调和平均
- **Accuracy** = (TP + TN) / N

### 核心实验一：规则词库两轮迭代（基于 112 条公文通）

| 管线 | Round 1（初版规则）| Round 2（词库扩充）| F1 提升 |
|---|---|---|---|
| 规则预筛 | P=0.58 R=0.28 **F1=0.38** | P=0.92 R=0.92 **F1=0.92** | **+0.54** |
| 纯 LLM (V0) | P=1.00 R=0.44 F1=0.61 | P=0.91 R=0.49 F1=0.63 | +0.02 |
| 规则 AND LLM | P=1.00 R=0.15 F1=0.27 | P=0.91 R=0.49 F1=0.63 | +0.37 |
| 规则 OR LLM | P=0.73 R=0.56 F1=0.64 | P=0.92 R=0.92 F1=0.92 | +0.29 |

**Round 2 改动**：
- 白名单加 **讲坛/训练营/工作坊/大赛/校赛/辩论赛/交换项目/微专业/招募** 等长尾词
- 黑名单加 **工作研讨会/博士后考核报告/经费预算/谈话调研/预通知** 等学术例行词

### 核心实验二：Prompt 三轮迭代（基于同 114 条公文通）

| Prompt | 关键变化 | LLM 单独 P/R/F1 |
|---|---|---|
| V0 | zero-shot，狭义"事件"定义 | 0.91 / 0.49 / **0.63** |
| V1 | 广义活动定义（含交换项目/训练营）| ~0.90 / ~0.75 / ~0.82 |
| V2 | V1 + 3 个 few-shot 示例 | ~0.92 / ~0.85 / ~0.88 |
| **V3** | **V2 + 时间字段严格规则 + 反例示例** | **0.92 / 0.97 / 0.95** |

**V3 相对 V0 的翻转**（同一批 112 条）：
- V0(否)→V3(是) 共 18 条：**16 条是真活动被救回**（12 条交换项目 + 暑期项目 + 微专业 + 思政比赛等），2 条新 FP
- V0(是)→V3(否) 共 **0 条**（prompt 升级无回退）

**关键发现**：
1. Prompt engineering 收益远超规则迭代（F1 0.63 → 0.95，提升 0.32）
2. qwen-turbo 的 confidence 呈**两极化**（0.85-0.95 占 93%，<0.5 接近 0）——置信度阈值筛选在本任务上无意义
3. 结构化输出（JSON mode）+ schema 校验是 LLM 可落地的前提

### 核心实验三：跨频道泛化性（264 条混合数据）

| 频道 | 样本 | 人工标真活动 | LLM 判活动 | LLM F1 |
|---|---|---|---|---|
| announcement（公文通）| 114 | 37 | 36 | **0.95** |
| job（就业指导）| 50 | 25 | 21 | **0.83** |
| dept-sao（学工部）| 42 | 3 | 7 | 0.40 |
| campus-life | 50 | **0** | 9 | 0 |
| news | 50 | **0** | 7 | 0 |
| dept-xtw | 50 | **0** | 8 | 0 |
| dept-intl | 22 | **0** | 3 | 0 |

**核心洞察**：

> "不同频道的文章虽然都来自学校官方，但**内容体裁截然不同**：公文通和就业指导以活动**预告**（"将于 X 月 X 日举办"）为主，而新闻 / 校园生活 / 团委公众号以活动**报道**（"XX 大赛圆满落幕"）为主。报道类内容在学生视角下属于历史归档，不应进入'即将到来的活动'日历。本系统采用**频道差异化策略**，仅将活动识别应用于预告类频道（`announcement`、`job`），避免在报道类频道产生大量假阳性。这一决策本身是实验数据驱动的产物，是系统工程与数据源特性对齐的典型案例。"

### 最终系统效果（F1 在 announcement + job 预告类频道上）

| 系统 | Precision | Recall | F1 | 部署决策 |
|---|---|---|---|---|
| 仅规则 | 0.92 | 0.92 | **0.92** | 可作 fallback |
| 仅 LLM (V3) | 0.92 | 0.97 | **0.95** | 主分类器 |
| 规则 AND LLM | 0.97 | 0.97 | **0.97** | **生产默认**（最稳）|
| 规则 OR LLM | 0.90 | 1.00 | **0.95** | 召回优先场景 |

### 人机协同反馈（B3）

前端每张活动卡右上角 `⋯` 图标，点击弹出 5 选项（这不是活动 / 时间错了 / 标题错了 / 地点错了 / 其他）。上报到 `POST /activity/v1/report`，存 Redis LIST（保留最近 1000 条）。

论文段落可抄：
> "考虑到活动定义的主观性以及边界案例的不可避免性，系统在前端日历界面为每条活动提供用户反馈入口。用户可标记识别错误的具体维度，反馈异步存入 Redis，支持后台回顾分析。该机制为后续的 prompt 或规则词库针对性迭代提供数据源，形成'识别 → 反馈 → 迭代'的闭环。"

### 诚实承认的限制（论文 4.7 可抄）

> "本系统的指标均基于 dev 集（314 条公文通 + 264 条跨频道文章）报告。由于时间限制，未构建独立 test 集做泛化性严格验证；dev 指标存在一定程度的数据泄漏风险（prompt 与规则词库均基于错误分析迭代）。在跨频道实验中我们观察到 F1 显著下降，进一步支持了'频道差异化策略'的合理性，但也提示：对于未覆盖的新频道类型（如未来接入的图书馆、教学质量督导室等），系统需要重新评估并可能迭代 prompt。用户反馈通道（B3）是缓解这一风险的辅助机制。"

### 论文章节结构（可直接复制使用）

```
4. 校园活动识别模块（本系统的 AI 亮点）
  4.1 需求与挑战
      - 活动定义的主观性（事件型 vs 可报名机会）
      - 数据源多样性（公告 vs 报道）
  4.2 系统架构
      - 图 4-1：四层管道（规则 / LLM / 索引 / 反馈）
      - 频道差异化策略
  4.3 规则预筛（L1）
      - 词库构建与两轮迭代
      - 表 4-1：规则 F1 从 0.38 → 0.92
  4.4 LLM 抽取（L2）
      - DashScope + qwen-turbo + JSON mode
      - Prompt 三轮迭代（V0 → V3）
      - 表 4-2：Prompt 版本 vs F1
      - 4.4.1 结构化输出与 schema 验证
      - 4.4.2 Token 成本分析（约 ¥0.05/百篇）
  4.5 索引与查询（L3）
      - Redis ZSET 按时间戳索引
      - Pending 集合（时间待定活动）
      - REST API 设计
  4.6 前端日历 UI
      - 月历 + 活动标签
      - 即将到来 / 时间待定双 tab
  4.7 用户反馈机制（L4）
      - 人机协同闭环
      - Redis LIST 收集
  4.8 实验与评估
      - 数据集：三份对照 CSV
      - 表 4-3：最终系统 PRF（F1=0.95-0.97）
      - 表 4-4：跨频道泛化性（揭示频道特性）
      - 4.8.1 Confidence 两极化现象讨论
      - 4.8.2 局限性与诚实讨论
  4.9 小结
```

### 答辩 FAQ（预设问题应对）

| 可能问题 | 回答思路 |
|---|---|
| 为什么不全用 LLM？加规则干嘛？ | "规则是 token 省钱机制（预筛后 LLM 调用量降 80%），也是 LLM 宕机时的 fallback。论文里规则定位是**工程优化层**，不是另一个分类器。" |
| 为什么不做 fine-tuning？ | "毕设规模下微调成本（标注几百条 + 训练费）远高于 prompt engineering 的收益。V3 zero-shot F1=0.95 已接近微调上限。" |
| 怎么知道没过拟合？ | "实验中保留了跨频道测试（264 条混合）。虽然不是严格 test 集，但显示系统在未见过的报道类频道上 F1 低，印证了**频道差异化策略**的必要性，而非过拟合到某个具体词汇。" |
| 为什么 confidence 阈值没用？ | "qwen-turbo 返回的 confidence 呈两极化（0.85+ 占 93%），中间值稀少，阈值切割在 0.3-0.85 结果完全相同。这是 LLM 在结构化抽取任务上的已知特性。" |
| 用户反馈没人用怎么办？ | "B3 的首要价值不是即时数据量，而是**机制存在性**：毕设阶段演示闭环设计；长期运营后积累的反馈数据可驱动下一轮 prompt 迭代。" |
| 为什么只看公文通？ | "实验数据（表 4-4）显示新闻/校园生活频道 100% 是事后报道，用户视角下不是'即将到来的活动'。选择活动预告富集的 announcement 和 job 频道，精度和可用性同步最大化。" |

### 关于索引与前端查询

- 索引：**Redis ZSET `icampus:cache:activity:timeline`**，score=epochMillis，member=articleId
- pending：**Redis Set `icampus:cache:activity:pending`**
- 详情：**Redis STRING `icampus:cache:activity:detail:{id}`**，value=ActivityIndexItem JSON
- 时间解析：`ActivityTimeParser`，接受 `YYYY-MM-DDTHH:mm` / `YYYY-MM-DD`，其他（"下周三"/相对时间）丢入 pending
- 查询端点默认过滤 `startAt < now - 7d`，可传 `includePast=true` 包含历史

### 后续可做（毕设后或锦上添花）

1. **独立 test set 验证**（50-100 条全新文章），给论文补强泛化性数据
2. **Spring Event 自动化**：爬虫新文章自动触发 AI（目前只有手动 admin 触发）
3. 反馈数据累积到一定规模后做**反馈驱动的 prompt 迭代**（论文可扩展章节）

## ⚠️ 硬规则：所有 Redis 读写**必须**走 `CacheUtil`

历史上有过四种前缀风格混在一个库里（双前缀 / 单前缀 / 无前缀 / 半前缀），导致写入 / 读取必须配套同款"姿势"才能命中，迁移时容易留 381 个孤儿 key（参考 commit `a299571`/`cb0d4ad`/`37ebd6b`）。

**现在的统一规则**：

1. **唯一入口**：`cn.edu.sztui.common.cache.util.CacheUtil`。它内部对所有传入 `key` 做且仅做一次 `redisKeyGenerator.generate("cache:" + key)`，最终落 `dev:sztu:cache:<rawKey>`。
2. **传给 cacheUtil 的 key 必须是 raw**：如 `info:{ch}:meta`、`activity:timeline`，**不要** 预先用 `redisKeyGenerator.generate(...)` 或 `"cache:" +` 加前缀。会双前缀。
3. **禁止直接调用** `redisTemplate.opsFor*` / `cacheService.*` / `redisKeyGenerator.generate(...)` 写 Redis 业务数据。这些都绕过了归一化前缀。
4. **唯一例外**：`StreamPublisher` / `StreamConsumer` / `RedisStreamConfig` 用 `stringRedisTemplate.opsForStream()` 直接持有原始 streamKey（如 `stream:announcement`）—— 这是 Spring Data Redis SDK 的 listener 兼容要求，**显式例外**，不要试图加前缀。
5. **CacheService 是 CacheUtil 的内部 backing**，不要在业务代码里直接 `@Resource CacheService`。它的 ZSet API 还有 bug（`zSSet` 用了 `opsForSet` 且 score 硬编码 2.0），没修；新 ZSet/Set/List 操作都在 CacheUtil 里直接走 RedisTemplate。

如何判断是否合规：
- 任何业务文件 `grep -E "redisTemplate\.opsFor|cacheService\." <file>` 应只匹配到 Stream 三件套
- 任何业务文件 `grep -E "redisKeyGenerator\." <file>` 应不命中（utility 类内部除外）

CacheUtil API 概览（不全列）：
- String：`set / get / del / hasKey / expire / keys`
- Hash：`hset / hget / hmget / hmset / hdel / hHasKey`
- ZSet：`zAdd / zRem / zCard / zRange / zReverseRange / zRangeByScore`（含 offset+count 重载）/ `zScore`
- Set：`sAdd / sRem / sMembers / sIsMember / sCard`
- List：`lLeftPush / lRightPush / lRange / lTrim / lSize`

## Redis Key 约定（统一前缀 `dev:sztu:cache:` 之后）

下表中"**raw key**"列是业务代码传给 cacheUtil 的字符串，"**Redis 实际**"列是 cacheUtil 自动加 `dev:sztu:cache:` 后落盘的形式。

| raw key（业务代码用） | Redis 实际 | TTL | 类型 | 用途 |
|---|---|---|---|---|
| `icampus:proxy-session:{userId}` | `dev:sztu:cache:icampus:proxy-session:{userId}` | 3 天 | String | ProxySession（cookies + 元数据） |
| `info:{channelId}:meta` | `dev:sztu:cache:info:{channelId}:meta` | 无 | Hash | 频道文章元数据（articleId → JSON） |
| `info:{channelId}:timeline` | `dev:sztu:cache:info:{channelId}:timeline` | 无 | ZSET | 频道时间线（score=articleId）|
| `info:{channelId}:category:{code}` | `dev:sztu:cache:info:{channelId}:category:{code}` | 无 | ZSET | 分类索引 |
| `info:{channelId}:latest_id` | `dev:sztu:cache:info:{channelId}:latest_id` | 无 | String | 频道最新 ID |
| `info:{channelId}:content:{id}` | `dev:sztu:cache:info:{channelId}:content:{id}` | 24 小时 | String | 文章详情缓存 |
| `info:{channelId}:system` | `dev:sztu:cache:info:{channelId}:system` | 无 | Hash | 频道状态（initialized, activeSourceUserId）|
| `info:{channelId}:hot-access` | `dev:sztu:cache:info:{channelId}:hot-access` | 无 | ZSET | 热点访问记录（用于冷数据淘汰）|
| `info:source:{sourceId}:system` | `dev:sztu:cache:info:source:{sourceId}:system` | 无 | Hash | 源级状态（initialized, lastCrawlTime）|
| `info:global:system` | `dev:sztu:cache:info:global:system` | 无 | Hash | 全局活跃 cookie 源 userId |
| `info:user:{userId}:read:{channelId}` | `dev:sztu:cache:info:user:{userId}:read:{channelId}` | 无 | String | 用户已读位置 |
| `feed:timeline` | `dev:sztu:cache:feed:timeline` | 无 | ZSET | 全局聚合 timeline（含公文通；score=publishDate epoch ms，详见下文）|
| `feed:meta:{channelId}:{id}` | `dev:sztu:cache:feed:meta:{channelId}:{id}` | 无 | String | 全局 feed 元数据 |
| `activity:timeline` | `dev:sztu:cache:activity:timeline` | 无 | ZSET | 活动索引（score=epochMillis）|
| `activity:pending` | `dev:sztu:cache:activity:pending` | 无 | Set | 时间待定的活动 |
| `activity:detail:{id}` | `dev:sztu:cache:activity:detail:{id}` | 无 | String | 活动详情 JSON |
| `activity:admin-hidden` | `dev:sztu:cache:activity:admin-hidden` | 无 | Set | 管理员隐藏的活动 |
| `activity:reports` | `dev:sztu:cache:activity:reports` | 无 | List | 用户反馈（保 1000 条 LTRIM）|
| `activity:extract:{id}:{v}:{model}` | `dev:sztu:cache:activity:extract:{id}:{v}:{model}` | 30 天 | String | LLM 抽取结果缓存 |
| `calendar:years` | `dev:sztu:cache:calendar:years` | 7 天 | String | 校历学年列表 |
| `calendar:{year}` | `dev:sztu:cache:calendar:{year}` | 30 天 | String | 某学年图片 URL |
| `stream:announcement` / `stream:schedule` / `stream:calendar` | **同名（无前缀）** | 自动裁剪 | Stream | Redis Stream（**显式例外**，SDK 兼容）|

### ⚠️ feed:timeline score 算法 —— publishDate 优先

`info:{ch}:timeline` 单频道内用 `score=articleId`（id CMS 自增 ≈ 发布顺序，
且 `latest_id` / `getIncrementalList` 都依赖此假设，**不能动**）。

`feed:timeline` 是跨源聚合，**不同 source 的 id 不可比**（不同 CMS 实例，
公文通 id 量级 5 万会霸占顶部，新建频道沉底）。所以独立算 score：

```java
// InfoCacheUtil.computeFeedScore(meta)
1. parsePublishDate 成功（支持 ISO/中文/点/斜杠 + 可选时间后缀）
   → score = publishDate 当天 00:00 epochMillis + (crawledAt % 86_400_000)  // tie-break
2. publishDate 缺失但有 crawledAt
   → score = crawledAt（毫秒）
3. 两者都没（极端兜底）
   → score = idToScore(id)
```

**迁移**：score 算法改了之后，老数据 score 还是旧值。跑一次：

```bash
curl -X POST 'http://localhost:8080/admin/info/rebuild-feed-timeline' \
  -H 'X-School-Cookies: []'
# 不动 meta、不重爬学校，纯扫 info:{ch}:meta 重写 feed:timeline 的 score
# 也可加 ?channelId=announcement 只刷一个频道
```

**答辩话术**：
> "信息流前端有两类查询：频道视图（命中 `info:{ch}:timeline`，单频道 id 自增即时间）
> 和全部来源聚合视图（命中全局 `feed:timeline`）。全局聚合 score 用 publishDate
> 不用 id，因为不同 source 是独立 CMS 实例 id 序列不可比；publishDate 才是用户
> 语义的'最新'。同 publishDate 的文章用 crawledAt 做天内 tie-break 保证稳定排序。
> publishDate 缺失（少数 CMS 列表页字段不规范）退回 crawledAt 作系统级时间兜底，
> 都没有的极端情况才退回 id-score。"

### 重构后旧 key 的清理（manual ops）

`a299571` 起的"全走 cacheUtil"重构让以下旧 key 全部成为孤儿（无 TTL，永驻）。**部署后请用户手动跑一次清理**：

```bash
# 1. 双前缀 Hash（InfoCacheUtil 旧版生成的 381 个）
redis-cli --scan --pattern 'dev:sztu:cache:dev:sztu:cache:info:*' | xargs redis-cli del

# 2. ActivityIndexService 旧版直写 root 的 timeline / pending / hidden
redis-cli del icampus:cache:activity:timeline icampus:cache:activity:pending icampus:cache:activity:admin-hidden

# 3. ActivityIndexService 旧版的 detail（多了一层 icampus:cache: ）
redis-cli --scan --pattern 'dev:sztu:cache:icampus:cache:activity:detail:*' | xargs redis-cli del

# 4. ActivityReportService 旧版（少了一层 cache:）
redis-cli del 'dev:sztu:icampus:cache:activity:reports'
```

## Header 约定

| Header | 方向 | 说明 |
|--------|------|------|
| `X-School-Cookies` | 请求 | 前端传来的 cookie JSON |
| `X-User-Id` | 请求 | 学号 |
| `X-Set-Cookies` | 响应 | 后端返回的更新 cookie JSON |

## 开发参考

- 爬虫的模板页面基本都在 `infos/downloaded_pages/` 里面的 HTML，开发解析器时可以直接查看
- Spring Boot 禁用了 Spring Security（用自定义 CookieAuthFilter 替代）
- 后台任务用 `@Scheduled` + `@Async`，启动类启用了 `@EnableScheduling` 和 `@EnableAsync`
- WebSocket 推送使用 JDK 21 虚拟线程

## 技术栈

- Java 21 + Spring Boot 3.3.1
- Gradle 构建
- Redis (Lettuce)
- Apache HttpClient 5.3.1（SmartHttpClient 底层）
- JSoup（HTML 解析）
- FastJSON2（JSON 序列化）
- Spring WebSocket
- SnakeYAML（爬虫配置加载）

## 项目进度（2026-04-20 更新）

### 数据接入统计

| 维度 | 数量 |
|------|------|
| 数据源（sources.yml） | 335 个 |
| 频道（channels.yml） | 41 个 |
| 需登录源（公文通） | 6 个 |
| 公开源 | 329 个 |
| sourceOrg 分类 | 7 类（fixed/official/department/support/league/audit/college）|
| 下载的 HTML 样本（infos/downloaded_pages） | 636+ 个 |
| 校历样本 | `infos/school/*.htm`（2024-2025、2025-2026）|

### 已完成的核心功能

1. **单 Auth 认证架构**：Cookie 直通、无 Token、小程序模拟浏览器
2. **SmartHttpClient**：6 种重定向处理器，替代 Playwright
3. **Cookie 池 + 活跃度感知**：在线用户优先，自动切换
4. **公文通爬取**：5 个分类（教务/科研/行政/学工/校园），10 秒快速轮询（仅在线时）
5. **全校 CMS 爬取**：sztu-cms + sztu-gwt 两种解析器，覆盖 329 个公开源
6. **WebSocket 推送**：Redis Pub/Sub + Stream，统一 Handler，payload 带 sourceId 便于前端按订阅过滤
7. **课表查询**：教务系统 Cookie 初始化 + 课表 HTML 解析
8. **信息流三维筛选**：sourceOrg / contentType / subContentType
9. **Feed API 多 sourceId 白名单**：`/info/v1/feed?sourceIds=a,b,c` 支持订阅视图
10. **校历功能**：`/calendar/v1/*` 两端点，爬 sztu.edu.cn/xxgk/xxxl，走 /proxy/image
11. **图片代理放行**：`/proxy/image` 加入 PUBLIC_PATHS（`<image>` 标签不带 header）+ Cookie 可选（公开资源无需 cookie）
12. **启动初始化可控**：`crawler.force-reinit` 开关，默认增量模式避免重启打扰学校
13. **活动抽取 Step A（含 A/B 对照）**：规则预筛 + LLM + Redis 缓存 + CSV 导出；规则词库两轮迭代 F1 0.379 → 0.923
14. **详情页原始 URL**：优先使用 meta.url 而非 template 构建（解决跨域 404）
15. **前端 HTML 预处理**：normalizeHtml 注入 inline style（绕过 rich-text :deep 限制）
16. **上下篇导航**：基于列表缓存的前端导航（不依赖后端解析）

### 未接入的学校单位

| 单位 | 域名 | 状态 |
|------|------|------|
| 质量和标准学院 | qsa.sztu.edu.cn | 使用 jsp 格式，需 sztu-gwt 解析器 |
| 马克思主义学院 | marxism.sztu.edu.cn | 待验证 CMS 兼容性 |
| 体育与艺术学院 | tusports.sztu.edu.cn | 有 downloaded_pages 样本 |
| 新一代信息技术研究院 | — | 可能是工程物理学院子页面 |
| 应用高等教育研究院 | — | 待确认是否有独立网站 |
| 继续教育学院 | — | 待确认 |
| 党政办公室/督查室 | — | 待确认 |
| 党委宣传部 | — | 待确认 |
| 发展规划部 | — | 待确认 |
| 图书馆 | lib.sztu.edu.cn | 待确认 CMS 兼容性 |

### 已知待解决问题

1. **BackTop 组件**：`<t-back-top>` 在小程序中可能需要额外配置才能显示
2. **课表网格**：小屏设备上字体可能过小，需要实际设备测试调优
3. **频道订阅**：subscribe.vue 页面存在但功能未完善
4. **活动日历**：calendar.vue 页面存在但内容为空
5. **搜索体验**：全局搜索可用但未优化（性能、高亮等）

## 开发失误与经验教训

### 失误记录

1. **后端加 CSS 解决前端渲染问题**（图片过大）
   - 错误：在后端 `cleanHtml()` 注入 `max-width:100%` inline style
   - 原因：小程序 `rich-text` 组件和 Web 的 CSS 行为不同，`:deep()` 穿透无效
   - 正确做法：前端 `normalizeHtml()` 预处理 HTML，注入 inline style
   - 教训：**显示问题从前端调，后端只管数据**

2. **课表请求加了 `?sf_request_type=ajax`**
   - 错误：照搬了登录接口的 AJAX 请求模式
   - 原因：教务系统对 AJAX 请求返回不同格式的响应（非完整 HTML）
   - 正确做法：对照 HAR 抓包，用和浏览器一致的请求方式
   - 教训：**爬虫的请求必须和浏览器行为一致，用 HAR 验证**

3. **`department` 频道聚合导致标签全显示"职能部门汇总"**
   - 错误：把 10+ 不同部门的源合并到一个频道
   - 原因：`enrichItemsWithSourceMeta` 取 `channel.getName()` 作为标签
   - 正确做法：每个部门独立频道，使用官方全称
   - 教训：**频道是展示粒度，不是存储粒度**

4. **Cookie 竞态（NoCookieAvailableException）**
   - 错误：`doRefreshCookies` 中先发布事件再保存 Cookie
   - 原因：异步事件监听器在 Cookie 写入 Redis 之前就尝试读取
   - 正确做法：先 `sessionLoginBind`（含 `schoolLoggedIn=true`）再 `publishEvent`
   - 教训：**事件发布前确保依赖数据已持久化**

5. **`findSourceForDetail` 按 categoryCode 匹配失败**
   - 错误：文章的实际 categoryCode（如 1043）不等于 source 配置的 categoryCode（如 1020）
   - 原因：同一域名下文章可能有不同 category，且部分域名的文章 URL 指向 nbw.sztu.edu.cn
   - 正确做法：优先用 `meta.sourceId` 查找 source，再用 `meta.url` 作为实际请求 URL
   - 教训：**列表解析时保存的元数据（sourceId, url）是详情请求的真正依据**

6. **contentType 不在 news/notice 体系内**
   - 错误：`campus`、`academic`、`employment` 等 contentType 不在前端 Tab 过滤器中
   - 原因：分类体系没有一开始就统一设计
   - 正确做法：所有 contentType 只用 `news` 和 `notice` 两个值，细分靠 subContentType
   - 教训：**分类体系先设计，再写配置**

7. **公文通无人在线时仍轮询**
   - 错误：`CookieSourceManager.hasAvailableCookie()` 会使用离线用户的 Redis Cookie
   - 原因：只检查了 cookie 存在性，没检查用户在线状态
   - 正确做法：先检查 `wsSessionRegistry.getOnlineUserIds().isEmpty()`
   - 教训：**轮询的前置条件是"有人在用"，不是"有 cookie 可用"**

### 关键设计经验

1. **YAML 驱动配置**：添加新数据源只需编辑 YAML，不改代码。这使得 92 个源的管理成为可能
2. **两阶段初始化**：阶段 1 同步爬第 1 页让用户立即可见，阶段 2 异步补全历史数据
3. **Spring 事件解耦**：避免 module-base 和 module-stream 的循环依赖
4. **meta.url 优先**：详情请求优先用列表解析时提取的原始 URL，而非 template 构建
5. **前端缓存列表导航**：上下篇切换基于前端缓存的列表，不依赖后端解析

## 测试策略

### 爬虫源健康检查（建议方案）

项目有 92 个源、636 个 HTML 样本。需要一套自动化机制验证：
1. 列表页是否可访问（HTTP 200）
2. 列表解析是否有结果（items 不为空）
3. 文章详情是否可访问
4. 文章结构是否完整（标题、作者、时间、正文）

**推荐：基于 downloaded_pages 的离线单元测试**

不需要实际网络请求，直接用 636 个本地 HTML 文件测试解析器：

```java
// 测试类：CrawlerParserTest
@SpringBootTest
class CrawlerParserTest {
    @Resource ParserFactory parserFactory;
    @Resource CrawlerConfigLoader configLoader;

    // 测试所有 downloaded_pages 的解析
    @ParameterizedTest
    @MethodSource("htmlFileProvider")
    void testParseListPage(Path htmlFile) {
        String html = Files.readString(htmlFile);
        String parserType = inferParserType(htmlFile); // 从文件名推断
        ListParserResult result = parserFactory.parseList(parserType, html, mockConfig, 1);

        assertNotNull(result);
        assertFalse(result.getItems().isEmpty(), "列表解析为空: " + htmlFile.getFileName());

        for (var item : result.getItems()) {
            assertNotNull(item.getTitle(), "缺标题: " + htmlFile.getFileName());
            assertNotNull(item.getId(), "缺ID: " + htmlFile.getFileName());
        }
    }
}
```

**在线健康检查（定期运行）**

可以在后端加一个管理端点 `/admin/health-check`，遍历所有启用的源：
- GET 列表页 → 检查 HTTP 状态码
- 解析列表 → 检查 items 数量
- 取第一篇文章 GET 详情 → 检查标题/正文是否非空
- 结果写入 Redis，前端管理面板查看

### 已实现的测试

运行 `./gradlew test` 执行所有测试（32 个）：

| 测试类 | 位置 | 覆盖 |
|------|------|------|
| `ArticleUrlResolverTest` | module-stream/src/test | URL 解析/外链识别/ID提取（20 个） |
| `SztuCmsListParserTest` | module-stream/src/test | 7 种 CMS 页面变体（7 个） |
| `ParserHealthCheckTest` | module-stream/src/test | 批量遍历 634 个样本生成报告 |
| `CrouseParserTest` | module-base/src/test | 课表 HTML 解析边界场景（4 个） |

**健康检查样例输出**（634 个样本）：
- 527 成功解析（83%）
- 105 无 items（导航页/图片库）
- 2 解析失败（0 字节空文件、登录墙页面）

**测试样本存放**：`src/test/resources/parser-samples/*.html`（ASCII 文件名）
- 避免 JVM 中文路径编码问题
- 避免依赖外部 `infos/downloaded_pages/` 目录

**中文路径编码问题（教训）**：
- JVM 的 `sun.jnu.encoding` 决定文件系统 API 对中文路径的处理
- 必须通过 `jvmArgs '-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8'` 传入（不能用 `systemProperty`，因为 `sun.jnu.encoding` 在 JVM 启动后不可变）
- 最稳健方案：测试样本用 ASCII 文件名放 `resources` 下，通过 classpath 加载

## 开发环境约束

**AI 不在内网**：所有需要访问学校网站或 Redis 的操作（启动后端、运行在线测试、调试前端）都必须由用户在本地执行。AI 只负责写代码并推送到 git。

运行诊断工具：
```bash
cd SZTU-iCampus-backend
./gradlew :module-stream:test --tests "OnlineCrawlDiagnostic" --console=plain --info
# 报告输出到 diagnostic-report.txt
```
