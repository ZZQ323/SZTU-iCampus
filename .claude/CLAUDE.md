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
不用 MySQL，只用 Redis。Redis 暂存文章摘要方便搜索。理论上 Redis 不适合持久化，但项目数据可以通过重新爬取恢复。

### 2. SmartHttpClient 替代 Playwright
自研的 `SmartHttpClient` 处理学校 WebVPN 的复杂重定向链，6 个重定向 handler 按优先级排列（责任链模式）。不要引入 Playwright 或其他 headless browser。

### 3. Spring 事件优先于直接依赖
遇到循环依赖时，**优先使用 Spring 事件**（`ApplicationEventPublisher`），不要尝试用 `@Lazy` 或拆分 jar 包。原因：阿里云仓库同步很慢，jar 分发容易出问题。

### 4. getSms 使用空 session
`getSms()` 必须创建全新的空 SmartSession，不能复用前端传来的旧 cookie。旧 cookie 会干扰 IDP 路由，导致落地到 `idp/AuthnEngine` 而非 `ActionAuthChain?entityId=webvpn`。

### 5. Cookie 有效性由学校判断
后端不做 cookie 过期预测，不缓存登录状态。所有状态查询都是真实访问学校 gateway 并解析返回页面。

### 6. refresh 优先于 init
大多数"会话过期"场景只需要调 `/auth/v1/session/refresh`（像浏览器按 F5），不需要 `/auth/v1/session/init`（清缓存重来）。有 cookie 就 refresh，没 cookie 才 init。是否登录只有学校后端说了算，胶水层说不算。

### 7. 所有返回 cookies 的接口都要设 X-Set-Cookies header
`/auth/v1/status` 内部调了 `doRefreshCookies()` 拿到了新鲜 cookies，必须通过 `X-Set-Cookies` response header 返回给前端。否则前端 cookies 不会被刷新，导致后续请求用过期 cookies。

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

## Redis Key 约定

| Key 模式 | TTL | 用途 |
|---------|-----|------|
| `icampus:proxy-session:{userId}` | 3 天 | ProxySession（cookies + 元数据） |
| `icampus:cache:info:list:{sourceId}` | 无 | 信息列表缓存 |
| `icampus:cache:info:detail:{sourceId}:{id}` | 无 | 文章详情缓存 |
| `icampus:cache:info:source:{sourceId}:system` | 无 | 爬虫状态 |
| `stream:announcement` / `stream:schedule` / `stream:calendar` | 自动裁剪 | Redis Stream |

## Header 约定

| Header | 方向 | 说明 |
|--------|------|------|
| `X-School-Cookies` | 请求 | 前端传来的 cookie JSON |
| `X-User-Id` | 请求 | 学号 |
| `X-Set-Cookies` | 响应 | 后端返回的更新 cookie JSON |

## 技术栈

- Java 21 + Spring Boot 3.3.1
- Gradle 构建
- Redis (Lettuce)
- Apache HttpClient 5.3.1（SmartHttpClient 底层）
- JSoup（HTML 解析）
- FastJSON2（JSON 序列化）
- Spring WebSocket
