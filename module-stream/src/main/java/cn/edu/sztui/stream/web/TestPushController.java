package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.application.service.StreamPushService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 测试推送端点 —— 给"挂机几天没真公文"的截图困境用。
 * <p>
 * <b>不要在生产开启</b>。仅论文截图 / 演示 / 联调用。会真往 announcement 频道写 N 条
 * 假数据 + 真推 WS，前端会真的弹 badge / 看到推送。
 * <p>
 * 用法：
 * <pre>
 * # 推 5 条假数据，30 秒后自动清理（前端展示完毕）
 * curl -X POST http://localhost:8080/admin/test/push \
 *   -H "X-School-Cookies: []" \
 *   -H "Content-Type: application/json" \
 *   -d '{"count": 5, "titlePrefix": "📢 关于举办...", "autoCleanupSeconds": 30}'
 *
 * # 不自动清理（截图用，自己事后清）
 * curl -X POST http://localhost:8080/admin/test/push \
 *   -H "X-School-Cookies: []" \
 *   -H "Content-Type: application/json" \
 *   -d '{"count": 3, "autoCleanupSeconds": 0}'
 *
 * # 手动清掉之前残留的测试数据
 * curl -X POST http://localhost:8080/admin/test/push-cleanup \
 *   -H "X-School-Cookies: []"
 * </pre>
 */
@Slf4j
@Tag(name = "测试·假推送")
@RestController
@RequestMapping("/admin/test")
public class TestPushController {

    /** 所有测试数据的 sourceId 标记，便于一键 cleanup */
    private static final String TEST_SOURCE_ID = "__test_push__";

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private StreamPushService streamPushService;

    @Resource
    private CacheUtil cacheUtil;

    /** 单例 scheduler，处理延时 cleanup */
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "test-push-cleanup");
                t.setDaemon(true);
                return t;
            });

    @PostMapping("/push")
    @Operation(summary = "造假推送（论文截图用，会真写 Redis 真推 WS）")
    public Result push(@RequestBody(required = false) PushRequest req) {
        if (req == null) req = new PushRequest();
        String channelId = req.getChannelId() == null ? "announcement" : req.getChannelId();
        int count = req.getCount() == null ? 5 : Math.max(1, Math.min(20, req.getCount()));
        String titlePrefix = req.getTitlePrefix() == null
                ? "📢 关于举办测试活动的通知" : req.getTitlePrefix();
        int autoCleanupSec = req.getAutoCleanupSeconds() == null ? 0 : req.getAutoCleanupSeconds();

        // 生成 N 条假 item，ID 用 currentTimeMillis*1000+i 保证比所有现有 ID 大
        long base = System.currentTimeMillis() * 1000L;
        String latestIdBefore = infoCacheUtil.getLatestId(channelId);
        List<InfoItemMeta> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String today = LocalDate.now().toString();
        for (int i = 0; i < count; i++) {
            String id = String.valueOf(base + i);
            InfoItemMeta meta = InfoItemMeta.builder()
                    .id(id)
                    .url("https://example.com/test/" + id)
                    .title(titlePrefix + (count > 1 ? " #" + (i + 1) : ""))
                    .channelId(channelId)
                    .sourceId(TEST_SOURCE_ID)
                    .sourceOrg("test")
                    .sourceOrgName("测试推送")
                    .contentType("notice")
                    .subContentType("general-notice")
                    .crawledAt(System.currentTimeMillis())
                    .publishDate(today)
                    .build();
            infoCacheUtil.saveMeta(channelId, meta);
            items.add(meta);
            ids.add(id);
        }
        String newLatestId = ids.get(ids.size() - 1);
        infoCacheUtil.setLatestId(channelId, newLatestId);

        // 构造 WS payload —— 跟 CrawlEngine.buildBroadcastPayload 一致
        Map<String, Object> data = new HashMap<>();
        data.put("channelId", channelId);
        data.put("ids", ids);
        data.put("latestId", newLatestId);
        data.put("latestTitle", items.get(0).getTitle());
        data.put("sourceId", TEST_SOURCE_ID);
        data.put("items", items);
        streamPushService.broadcastNewAnnouncements(data);

        log.info("[test-push] 已推送 {} 条假数据 channel={} latestId={}→{}",
                count, channelId, latestIdBefore, newLatestId);

        if (autoCleanupSec > 0) {
            final List<String> idsToClean = new ArrayList<>(ids);
            final String chId = channelId;
            scheduler.schedule(() -> {
                try {
                    cleanupItems(chId, idsToClean);
                    log.info("[test-push] auto-cleanup 完成 channel={} count={}", chId, idsToClean.size());
                } catch (Exception e) {
                    log.warn("[test-push] auto-cleanup 失败: {}", e.getMessage());
                }
            }, autoCleanupSec, TimeUnit.SECONDS);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("count", count);
        resp.put("ids", ids);
        resp.put("channelId", channelId);
        resp.put("latestIdBefore", latestIdBefore);
        resp.put("latestIdAfter", newLatestId);
        resp.put("autoCleanupSeconds", autoCleanupSec);
        return Result.ok(resp);
    }

    @PostMapping("/push-cleanup")
    @Operation(summary = "清理所有 sourceId=__test_push__ 的残留测试数据")
    public Result cleanup(@RequestParam(defaultValue = "announcement") String channelId) {
        // 扫频道 timeline 找出所有 sourceId=__test_push__ 的，清掉
        var ids = scanTestIds(channelId);
        cleanupItems(channelId, ids);
        log.info("[test-push] manual-cleanup channel={} removed={} items", channelId, ids.size());
        return Result.ok(Map.of("channelId", channelId, "removed", ids.size(), "ids", ids));
    }

    /**
     * 重推现有真实数据（论文截图首选 —— 不污染 Redis、不调学校）。
     * <p>
     * 从 channel timeline 顶部取 N 条**已存在的真实文章**，直接走
     * {@code broadcastNewContent} 重推一次。前端收到 WS 推送 → badge/队列/列表
     * 都会刷出真实条目（标题/单位/日期都是真的）。
     * <p>
     * <b>零副作用</b>：不改 latestId，不重写 meta，不动学校请求。
     * 用法：
     * <pre>
     * curl -X POST "http://localhost:8080/admin/test/rebroadcast?channelId=announcement&count=3" \
     *   -H "X-School-Cookies: []"
     * </pre>
     */
    @PostMapping("/rebroadcast")
    @Operation(summary = "重推现有真实数据（不污染 Redis）")
    public Result rebroadcast(
            @RequestParam(defaultValue = "announcement") String channelId,
            @RequestParam(defaultValue = "3") Integer count,
            @RequestParam(defaultValue = "0") Integer fromIndex) {
        int n = Math.max(1, Math.min(20, count));
        int start = Math.max(0, fromIndex);
        // 从 timeline 倒序取 [fromIndex, fromIndex + count) 范围内的真实文章 id
        var ids = cacheUtil.zReverseRange("info:" + channelId + ":timeline", start, start + n - 1);
        if (ids == null || ids.isEmpty()) {
            return Result.ok(Map.of("channelId", channelId, "count", 0, "msg", "频道无内容"));
        }
        List<InfoItemMeta> items = new ArrayList<>();
        List<String> hitIds = new ArrayList<>();
        for (Object idObj : ids) {
            InfoItemMeta meta = infoCacheUtil.getMeta(channelId, idObj.toString());
            if (meta != null) {
                items.add(meta);
                hitIds.add(meta.getId());
            }
        }
        if (items.isEmpty()) {
            return Result.ok(Map.of("channelId", channelId, "count", 0, "msg", "meta 缺失"));
        }
        InfoItemMeta head = items.get(0);
        String latestId = head.getId();
        Map<String, Object> data = new HashMap<>();
        data.put("channelId", channelId);
        data.put("ids", hitIds);
        data.put("latestId", latestId);
        data.put("latestTitle", head.getTitle());
        data.put("sourceId", head.getSourceId());
        data.put("items", items);
        streamPushService.broadcastNewAnnouncements(data);
        log.info("[test-push/rebroadcast] channel={} count={} fromIndex={} firstTitle='{}'",
                channelId, items.size(), start, head.getTitle());
        return Result.ok(Map.of(
                "channelId", channelId,
                "count", items.size(),
                "fromIndex", start,
                "ids", hitIds,
                "firstTitle", head.getTitle()
        ));
    }

    private List<String> scanTestIds(String channelId) {
        // timeline ZSET 是 raw "info:{ch}:timeline"
        var members = cacheUtil.zReverseRange("info:" + channelId + ":timeline", 0, 100);
        List<String> hits = new ArrayList<>();
        if (members == null) return hits;
        for (Object m : members) {
            String id = m.toString();
            InfoItemMeta meta = infoCacheUtil.getMeta(channelId, id);
            if (meta != null && TEST_SOURCE_ID.equals(meta.getSourceId())) {
                hits.add(id);
            }
        }
        return hits;
    }

    /**
     * 清掉 InfoCacheUtil.saveMeta 写过的 4 处：
     * - hash info:{ch}:meta 的 field
     * - zset info:{ch}:timeline 的 member
     * - zset feed:timeline 的 "channelId:id"
     * - string feed:meta:{channelId}:{id}
     */
    private void cleanupItems(String channelId, List<String> ids) {
        for (String id : ids) {
            try {
                cacheUtil.hdel("info:" + channelId + ":meta", id);
                cacheUtil.zRem("info:" + channelId + ":timeline", id);
                cacheUtil.zRem("feed:timeline", channelId + ":" + id);
                cacheUtil.del("feed:meta:" + channelId + ":" + id);
            } catch (Exception e) {
                log.warn("[test-push] cleanup id={} failed: {}", id, e.getMessage());
            }
        }
    }

    @Data
    public static class PushRequest {
        /** 频道 ID，默认 announcement */
        private String channelId;
        /** 推几条，1-20，默认 5 */
        private Integer count;
        /** 标题前缀 */
        private String titlePrefix;
        /** N 秒后自动清理；0 = 不自动清，截图用 */
        private Integer autoCleanupSeconds;
    }
}
