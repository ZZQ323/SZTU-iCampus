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

## 项目进度（2026-04-17 更新）

### 数据接入统计

| 维度 | 数量 |
|------|------|
| 数据源（sources.yml） | 92 个（88 启用 + 4 禁用） |
| 频道（channels.yml） | 37 个 |
| 需登录源（公文通） | 5 个 |
| 公开源 | 87 个 |
| 学院 | 14 个 |
| 职能部门 | 14 个 |
| 教辅科研单位 | 3 个 |
| 群团组织 | 2 个 |
| 党建工作源 | 21 个 |
| 下载的 HTML 样本（infos/downloaded_pages） | 636 个 |

### 分类覆盖

| contentType | subContentType | 源数量 |
|---|---|---|
| notice | general-notice | 30 |
| news | general-news | 26 |
| news | party | 21 |
| news | cooperation | 5 |
| news | student | 4 |
| news | academic | 3 |
| notice | employment | 1 |
| notice | admission | 1 |

### 已完成的核心功能

1. **单 Auth 认证架构**：Cookie 直通、无 Token、小程序模拟浏览器
2. **SmartHttpClient**：6 种重定向处理器，替代 Playwright
3. **Cookie 池 + 活跃度感知**：在线用户优先，自动切换
4. **公文通爬取**：5 个分类（教务/科研/行政/学工/校园），10 秒快速轮询（仅在线时）
5. **全校 CMS 爬取**：sztu-cms + sztu-gwt 两种解析器，覆盖 87 个公开源
6. **WebSocket 推送**：Redis Pub/Sub + Stream，统一 Handler
7. **课表查询**：教务系统 Cookie 初始化 + 课表 HTML 解析
8. **信息流三维筛选**：sourceOrg / contentType / subContentType
9. **前端 HTML 预处理**：normalizeHtml 注入 inline style（绕过 rich-text :deep 限制）
10. **上下篇导航**：基于列表缓存的前端导航（不依赖后端解析）
11. **详情页原始 URL**：优先使用 meta.url 而非 template 构建（解决跨域 404）

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
