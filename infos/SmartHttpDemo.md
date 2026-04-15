# SmartHttpClient 实现指南

## 一、文件结构

```
module-common/src/main/java/cn/edu/sztui/common/util/smarthttp/
├── SmartHttpClient.java              # 客户端接口
├── SmartHttpClientImpl.java          # 客户端实现
├── SmartRequest.java                 # 请求对象
├── SmartResponse.java                # 响应对象
├── SmartSession.java                 # 会话接口
├── SmartSessionImpl.java             # 会话实现
├── SmartCookie.java                  # Cookie 对象
├── SmartCookieConverter.java         # Cookie 转换工具
├── SmartHttpException.java           # 异常类
└── redirect/                         # 重定向处理器
    ├── RedirectHandler.java          # 处理器接口
    ├── LocationHeaderHandler.java    # Location Header 处理
    ├── MetaRefreshHandler.java       # Meta Refresh 处理
    ├── GLinesRedirectHandler.java    # g_lines 重定向（学校特定）
    ├── DataParameterRedirectHandler.java  # data 参数重定向（学校特定）
    └── JsRedirectHandler.java        # 通用 JS 重定向

module-base/src/main/java/cn/edu/sztui/base/application/service/impl/
├── AuthServiceImpl.java              # V1 实现（Playwright，保留）
└── AuthServiceV2Impl.java            # V2 实现（SmartHttpClient，新增）
```

## 二、依赖配置

### build.gradle (module-common)

```groovy
dependencies {
    // Apache HttpClient 5.x
    implementation 'org.apache.httpcomponents.client5:httpclient5:5.3.1'
    
    // Jackson（JSON 解析）
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'
    
    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

### application.yml

```yaml
smart-http:
  timeout-seconds: 30
  slow-timeout-seconds: 90
  max-connections: 500
  max-connections-per-route: 100
  user-agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"
```

## 三、切换实现

### 方式1：直接指定（推荐测试时使用）

```java
// AuthController.java
@Resource
@Qualifier("authServiceV2")  // 使用 V2
private AuthService authService;
```

### 方式2：配置切换（推荐生产使用）

```java
// AuthController.java
@Autowired
public AuthController(
    @Qualifier("${auth.service.impl:authService}") AuthService authService
) {
    this.authService = authService;
}
```

```yaml
# application.yml
auth:
  service:
    impl: authServiceV2  # 切换到 V2；使用 authService 切回 V1
```

## 四、核心改动说明

### 4.1 重定向处理流程

```
请求 → 检测重定向 → 跟随重定向 → ... → 最终响应
         │
         ├── LocationHeaderHandler (优先级 1)
         │     检测 301/302/307 + Location 头
         │
         ├── MetaRefreshHandler (优先级 2)
         │     检测 <meta http-equiv="refresh" ...>
         │
         ├── DataParameterRedirectHandler (优先级 5)
         │     检测 ?data=base64json 参数
         │
         ├── GLinesRedirectHandler (优先级 10)
         │     检测 g_lines = [{url:"..."}]
         │
         └── JsRedirectHandler (优先级 20)
               检测 window.location = "..."
```

### 4.2 会话管理

```
SmartSession
    │
    ├── 每个会话独立的 Cookie 存储
    │
    ├── 自动解析 Set-Cookie 响应头
    │
    ├── 自动构建 Cookie 请求头
    │
    └── 线程安全（ConcurrentHashMap）
```

### 4.3 与 V1 的兼容性

- **接口完全兼容**：实现相同的 `AuthService` 接口
- **Cookie 格式兼容**：使用 `SmartCookieConverter` 转换
- **缓存完全兼容**：复用 `AuthSessionCacheUtil`
- **事件完全兼容**：复用 `UserLoginEvent`

## 五、内存对比

| 场景 | Playwright (V1) | SmartHttpClient (V2) |
|------|-----------------|---------------------|
| 1 并发 | ~150MB | ~0.5MB |
| 50 并发 | ~3GB | ~25MB |
| 100 并发 | ~10GB | ~50MB |
| 200 并发 | ~20GB | ~100MB |

## 六、风险与降级

### 风险1：JS 重定向无法解析

**场景**：学校网站更新，出现新的 JS 重定向模式

**解决**：
1. 记录失败日志
2. 分析新模式
3. 添加新的 RedirectHandler

### 风险2：复杂动态 JS

**场景**：JS 重定向包含动态计算的 URL

**降级方案**：
```java
// 保留 Playwright 作为降级方案
@Resource
private PlaywrightBrowserPoolCommonsVersion browserPool;

// 在 V2 失败时降级到 V1
try {
    return doRefreshCookiesV2(...);
} catch (SmartHttpException e) {
    if (e.getMessage().contains("重定向次数超过限制")) {
        log.warn("V2 失败，降级到 Playwright");
        return doRefreshCookiesV1(...);  // 使用 Playwright
    }
    throw e;
}
```

## 七、测试清单

- [ ] 未登录状态，访问网关 → 重定向到登录页
- [ ] 已登录状态，访问网关 → 重定向到门户首页
- [ ] 获取短信验证码
- [ ] 短信登录
- [ ] 密码登录（如果支持）
- [ ] 登出
- [ ] Cookie 过期后重新登录
- [ ] 并发 50 请求性能测试
- [ ] 并发 200 请求稳定性测试

## 八、日志调试

```yaml
logging:
  level:
    cn.edu.sztui.common.util.smarthttp: DEBUG
    cn.edu.sztui.base.application.service.impl.AuthServiceV2Impl: DEBUG
```

输出示例：
```
[DEBUG] 请求 [1]: GET https://home.sztu.edu.cn/bmportal
[DEBUG] [LocationHeader] https://home.sztu.edu.cn/bmportal -> https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal
[DEBUG] 请求 [2]: GET https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal
[DEBUG] [GLinesRedirect] 检测到重定向
[DEBUG] 请求 [3]: GET https://webvpn.sztu.edu.cn/public/thdportal_login?...
...
[DEBUG] 最终 URL: https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal/index.portal, 重定向次数: 12
[INFO] 解析到用户信息: userId=202200202104, realName=张三, logined=true
```
