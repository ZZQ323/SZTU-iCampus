# 论文实验准备会话复盘（2026-05-01 → 05-05）

本次会话围绕"为论文 5/5 交稿补齐实验数据"展开，期间发现并修复了若干潜伏 bug，重构了 Redis 前缀混乱。下文按主题归档。

## 提交链路总览

```
402c1b4  fix(crawl):    dedup using hasMeta for non-numeric ID channels   ← acdm 假新增 bug 修复
54ca676  test:          HttpBackfillTest cleanup
263228a  test:          add 2.2 HTTP backfill correctness validation
12a37f7  test:          add 1.2 selector hit distribution + 2.1 WS push latency
0500f8d  feat:          kick off 4-day data collectors for 3.2 + 3.4     ← 长周期采集启动
c6858b0  docs:          "all Redis writes via CacheUtil" hard rule       ← 知识库更新
37ebd6b  refactor:      ActivityIndexService + ActivityReportService → cacheUtil
cb0d4ad  refactor:      InfoCacheUtil ALL Redis writes → cacheUtil       ← 双前缀 bug 修复
a299571  refactor:      extend CacheUtil with ZSet/Set/List ops          ← 重构基石
251ca86  test:          fix 1.1 to use actual doubled-prefix Redis keys
6fc4080  test:          1.1 source coverage V2 — switch to publishDate metric
086ae02  test:          move 1.1/3.1 tests to main module to fix bean-wiring
78a0f68  test:          add 1.1 source coverage + 3.1 cold-start latency
```

## 一、Redis 前缀大整治（5 commits，~240 LOC）

### 1.1 问题发现

实验 1.1 测试时发现 redis 里 4 种前缀模式共存：

| 模式 | 数量 | 写入路径 | 状态 |
|---|---|---|---|
| `dev:sztu:cache:dev:sztu:cache:info:*` | 381 | `InfoCacheUtil.generateKey(...)` 加一次前缀，再被 `cacheUtil.hset` 加一次 | 🐛 双前缀 bug |
| `dev:sztu:cache:feed:*` | 4931 | `generateKey + cacheService` 直走 | ✓ 单前缀 |
| `dev:sztu:cache:info:*` | 430 | `generateKey + redisTemplate.opsForZSet` 直走 | ✓ 单前缀 |
| `icampus:cache:activity:*`（在 root）| 0+ | `redisTemplate.opsFor*(KEY_LITERAL, ...)` 完全无前缀 | ⚠️ 不一致 |

### 1.2 根因

- `InfoCacheUtil.generateKey(k)` 内部 = `redisKeyGenerator.generate("cache:" + k)` = `dev:sztu:cache:` + k
- `cacheUtil.hset(k, v)` 内部 = `redisKeyGenerator.generate("cache:" + k)` = `dev:sztu:cache:` + k
- 两者拼一起就**双前缀**

### 1.3 修复策略（用户硬规则）

> "所有的数据必须全走 cacheUtil 没的商量"

实施 5 步：

| Step | Commit | 内容 |
|---|---|---|
| 1 | `a299571` | 给 `CacheUtil` 加 ZSet/Set/List API（118 行新方法）|
| 2 | `cb0d4ad` | `InfoCacheUtil` 44 处全部改走 cacheUtil；helper key 改 raw（不再 pre-prefix）|
| 3+4 | `37ebd6b` | `ActivityIndexService` + `ActivityReportService` 全走 cacheUtil，KEY 改 raw |
| 5 | `c6858b0` | CLAUDE.md 加"全走 cacheUtil"硬规则 + 新 Redis Key 表 |

### 1.4 例外（**不要改**）

- **Redis Stream**（`stream:announcement` / `stream:schedule` / `stream:calendar`）：Spring Data Redis 的 listener 直接持有原始 streamKey，加前缀会让 listener 找不到 stream → WS 推送瘫痪
- **CacheService**：保留作为 cacheUtil 的内部 backing；其 `zSSet` 方法已知有 bug（用了 `opsForSet` 且 score 硬编码 2.0），未修

### 1.5 旧 key 清理（用户已手动执行）

```bash
redis-cli --scan --pattern 'dev:sztu:cache:dev:sztu:cache:info:*' | xargs redis-cli del
redis-cli del icampus:cache:activity:timeline icampus:cache:activity:pending icampus:cache:activity:admin-hidden
redis-cli --scan --pattern 'dev:sztu:cache:icampus:cache:activity:detail:*' | xargs redis-cli del
redis-cli del 'dev:sztu:icampus:cache:activity:reports'
```

## 二、acdm-inbox 假新增 bug（commit 402c1b4）

### 2.1 现象

每 60s 周期：
```
增量爬取完成: source=acdm-ysgg, 新增 20 条
增量爬取完成: source=acdm-xxtz, 新增 20 条
WS broadcast: channel=acdm-notice, items=20
WS broadcast: channel=acdm-message, items=20
```
但前端 console 同步显示：`[WS] prepend skipped (all duplicates) ...`

### 2.2 根因

`CrawlEngine.filterNewItems` 用 `Long.parseLong` 比较 ID：

```java
try {
    threshold = Long.parseLong(cachedLatestId);
} catch (NumberFormatException e) {
    return items;  // ← 非数字 ID 整批返回，全部视为"新"
}
```

但：
- `acdm-message` 频道的 ID 是 `synthesizeXxtzId()` → `xxtz-<16-char-hex>`（非数字）
- `acdm-notice` 频道的 ID 是 `extractId(href)` → 学校用了 UUID 风格 ggid（非数字）

这俩频道的 `cachedLatestId` 永远是非数字 → `filterNewItems` 永远返回全部 → 永远"新增 20 条"。

### 2.3 影响

- 前端 ✓ 不受影响（前端有内容 hash 二次去重）
- WS 带宽 ⚠️ 每 2 min 推 40 条无用消息
- 学校爬取压力 🔴 每 60s 真打教务系统 1 次（违反"对学校友好"原则）
- **实验 3.4 cookie 池数据虚高** ⚠️ —— 9 小时 1732 borrows 大部分是这个 bug 贡献的

### 2.4 修复

非数字 ID 退化为 `infoCacheUtil.hasMeta(channelId, id)` 精确去重：

```java
private List<...> filterNewItems(items, cachedLatestId, channelId) {
    Long t = tryParseLong(cachedLatestId);
    return items.stream()
            .filter(item -> {
                if (t != null) {
                    try {
                        return Long.parseLong(item.getId()) > t;        // 数字快路径
                    } catch (NumberFormatException e) {
                        return !infoCacheUtil.hasMeta(channelId, item.getId());
                    }
                }
                return !infoCacheUtil.hasMeta(channelId, item.getId());  // 兜底
            })
            .collect(...);
}
```

数字 ID 频道（announcement / college / dept 等）零开销保留；非数字 ID 频道每条多 1 次 Redis HEXISTS（20 条 / 60s = 0.33 op/s，无关紧要）。

### 2.5 论文素材

- **可写为优化点**：发现并修复非数字 ID 频道的去重失效，使教务系统冗余轮询从每 60s 1 次降至 0
- **3.4 实验数据按时间分段**：402c1b4 之前的 borrows 计数有水分，之后的数据干净

## 三、实验体系（5 个 + 2 个长周期）

### 3.1 短周期（已出数）

| 实验 | 测试类 | 主结论 |
|---|---|---|
| 3.3 单/双 Auth LOC | git log 分析 | 简化重构净删 ~1200 行，移除 8 个独立类（含 JWT 状态机），换为 124 行 CookieAuthFilter |
| 1.1 数据源覆盖率 | `SourceCoverageTest` | 系统对 337 源接入率 94.36%，月活 18.40%，周活 8.31% |
| 3.1 冷启动延迟 | `ColdStartLatencyTest` | n=250 实测，p50=21ms p95=58ms p99=105ms，**100% ≤5s** |
| 1.2 选择器命中分布 | `SelectorHitDistributionTest` | 标题选择器 41% 靠启发式 + fallback 救回，正文选择器 99.81% 命中 |
| 2.1 WS 推送延迟 | `PushLatencyTest` | 1000 条 loopback，p50=1ms p99=4ms max=36ms，100% ≤100ms |
| 2.2 HTTP 兜底拉取 | `HttpBackfillTest` | 50/50 = 100% 完整率，单次 RTT 116ms |

### 3.2 长周期（5/1 启动 → 5/5 交稿前回收）

| 实验 | 采集器 | 输出文件 |
|---|---|---|
| 3.2 Redis 内存稳态 | `MemorySnapshotTask`（@Scheduled hourly）| `infos/runtime-trace/redis-memory.csv` |
| 3.4 Cookie 池命中率 | `CookiePoolMetrics`（埋点 + hourly snapshot）| `infos/runtime-trace/cookie-pool-snapshots.csv` + `cookie-pool-events.csv` |

**注意**：cookie-pool 计数器在 JVM 内存里，重启会清零。后处理时按"total_borrows 倒退"作分段标记。

## 四、值得记录的反直觉发现

### 4.1 实验 1.2：选择器分布是长尾，不是金字塔

之前预期"前 5 个选择器覆盖 90%+"，实测：

- 标题：18 个具体选择器累积只覆盖 58.83%；30.64% 靠"向最近 heading 标签回溯"启发式救回；10.53% 靠 HTML `<title>` 兜底
- 正文：99.81% 命中前 2 个选择器（这个符合预期）

**论文洞察**："标题选择器的分布扁平化，没有单一选择器占主导。这印证了博达 CMS 在 SZTU 各子域名下定制程度极高，**多级回退（具体 selector → 启发式 → fallback）是覆盖率从 58.83% 提升到 100% 的工程必要性，不是过度设计**。"

### 4.2 实验 1.1：lastCrawlTime ≠ "源活跃度"

V1 用 `info:source:{sid}:system.lastCrawlTime` 度量近 7 天活跃，结果 329/337 全部 ≤1d ≤7d ≤30d 完全相同。  
原因：调度器跑一次就更新该字段，与"是否拿到新内容"无关。

V2 改为扫每频道 timeline ZSET 里 InfoItemMeta 的 `publishDate`（学校原生发布日期），数据合理：18.40% 月活、8.31% 周活，符合"高校 CMS 多月级更新"预期。

### 4.3 实验 1.1：发现 381 个双前缀 key

为读出数据，必须复现 InfoCacheUtil 的双前缀路径才能命中。这反过来曝光了写入路径的不一致，引发了第一节的整治。

## 五、待办事项（5/5 交稿前）

- [x] 阶段 A 全部实验跑完出数（1.1 / 3.1 / 1.2 / 2.1 / 2.2 / 3.3）
- [x] acdm 假新增 bug 修复（commit 402c1b4）
- [ ] **5/5 交稿前**：导出 4 天累积的 `redis-memory.csv` + `cookie-pool-snapshots.csv` + `cookie-pool-events.csv`
- [ ] 按天聚合内存表 + cookie 池 event 时间线
- [ ] 论文 3.4 节诚实标注："5/2 修复 acdm 去重 bug 后，cookie 池借用速率降至 X 次/小时"
- [ ] 实验 2.3（WS 并发上限）按计划放弃，论文里写"理论估算，未实测压力上限"
- [ ] 实验 1.3（SmartHttp vs Playwright）按用户决定放弃

## 六、知识库更新

CLAUDE.md 已新增/修订两节：

1. **"硬规则：所有 Redis 读写必须走 CacheUtil"**（替代旧的"Redis Key 约定"）
2. **新 Redis Key 表**（每行明示 raw key + 实际 Redis key）
3. **重构后旧 key 的清理命令**（manual ops）

## 七、本次会话踩过的坑

1. **测试初放在 module-stream 失败**：bean wiring 缺 application.yml → 移到 main/src/test/ 用 `BaseMain.class`
2. **实验 1.1 V1 指标错**：lastCrawlTime 反映"调度器跑过"，不反映"产出新内容" → V2 改 publishDate
3. **测试读不到数据**：因为没复刻双前缀路径 → 反向曝光了写入路径 bug
4. **HttpBackfillTest 污染前端信息流**：50 条假数据驻留 → finally 块加自动 cleanup
5. **acdm-inbox 假新增**：实验 3.4 跑了 9 小时才发现，1732 borrows 数据被污染

每个坑都成了下一步的发现来源 —— 这是有效的"批判性思考"实践。
