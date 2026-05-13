# SZTU-iCampus-backend

校园小程序后端，Spring Boot 3.3 + Gradle 多模块。

- 项目前端：https://github.com/ZZQ323/SZTU-ICampus-miniprogram
- 项目后端：https://github.com/ZZQ323/SZTU-iCampus-backend

## 环境要求

- JDK 21（`build.gradle` 中 `sourceCompatibility = '21'`）
- Gradle：使用仓库自带的 `gradlew` / `gradlew.bat`，无需本机安装
- Redis 6+：**需自行安装并启动**，本项目不内置，默认端口 6379

## 配置

启动 profile 默认是 `dev`（见 `main/src/main/resources/application.yml`），运行前需要自行创建
`main/src/main/resources/application-dev.yml`（已在 .gitignore 中），至少包含：

```yaml
dev:
  localhost: 127.0.0.1   # Redis 主机
passwd:
  redis: your-redis-password   # 没设密码就留空字符串 ""
```

可选环境变量：
- `DASHSCOPE_API_KEY`：阿里云 DashScope，用于活动抽取（不配则相关功能不可用）

## 编译与运行

```bash
# 编译（首次会下载依赖，需联网）
./gradlew clean build           # Linux/macOS
gradlew.bat clean build         # Windows

# 跑主服务（main 模块）
./gradlew :main:bootRun
```

服务监听 `http://localhost:8080`。

## 模块结构

```
main/            启动模块（Spring Boot 主入口、application.yml）
module-base/     业务基础模块
module-common/   通用工具/常量
module-stream/   消息流/SSE/WebSocket 相关
```
