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

### 7. 所有返回 cookies 的接口都要设 X-Set-Cookies header
`/auth/v1/status` 内部调了 `doRefreshCookies()` 拿到了新鲜 cookies，必须通过 `X-Set-Cookies` response header 返回给前端。否则前端 cookies 不会被刷新，导致后续请求用过期 cookies。

### 8. 批判性思考
用户提出的想法和设计，都需要用 Plan 模式去质疑和审视。一个人说的总是会有纰漏，AI 应当做批判性分析。

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

### 教务 `/academic`
| 端点 | 方法 | 说明 |
|------|------|------|
| `/academic/v1/schedule` | GET | 课表查询 |

### 代理 `/proxy`
| 端点 | 方法 | 说明 |
|------|------|------|
| `/proxy/image` | GET | 代理学校图片（域名白名单） |
| `/proxy/attachment` | GET | 代理附件下载 |

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

YAML 驱动（`crawler/channels.yml` + `crawler/sources.yml`）：
- **频道**：28 个（公文通、教务、校园生活、各部门、各学院...）
- **数据源**：约 90 个，覆盖全校网站
- **解析器**：`sztu-gwt`（公文通 list.jsp）和 `sztu-cms`（学院 CMS 标准列表）
- 添加新数据源只需编辑 YAML，不需要改代码

## 两阶段初始化（CrawlEngine）

- **阶段 1（同步）**：爬第 1 页，立即存 Redis + 标记 initialized → 用户零等待
- **阶段 2（异步）**：后台线程池爬剩余页，每 3 页一批并发，批间 500ms → 无感补全

## Redis Key 约定

| Key 模式 | TTL | 用途 |
|---------|-----|------|
| `icampus:proxy-session:{userId}` | 7 天 | ProxySession（cookies + 元数据） |
| `icampus:cache:info:list:{sourceId}` | 无 | 信息列表缓存 |
| `icampus:cache:info:detail:{sourceId}:{id}` | 无 | 文章详情缓存 |
| `icampus:cache:info:source:{sourceId}:system` | 无 | 爬虫状态（lastCrawlTime, initialized） |
| `icampus:cache:info:active-source-user` | 无 | 当前爬虫使用的 Cookie 来源用户 |
| `stream:announcement` / `stream:schedule` / `stream:calendar` | 自动裁剪 | Redis Stream（保留 1000 条） |

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
