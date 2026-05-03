package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import cn.edu.sztui.stream.infrastructure.websocket.registry.WsSessionRegistry;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

/**
 * WS 推送诊断日志
 * <p>
 * 用户场景：挂机时收到的推送里包含旧消息（publishDate 早于本地已知的最新）。
 * 这意味着推送链某处把"已存在内容"当成"新内容"推了。该日志把每次推送的
 * 全量元信息落到 JSONL 文件，事后离线分析定位根因。
 * <p>
 * <b>每次推送写一行 JSON</b>：
 * <pre>
 * {
 *   "ts": "2026-05-02T18:30:01.123Z",
 *   "trigger": "crawlIncremental",       // 触发路径标签
 *   "channelId": "announcement",
 *   "sourceId": "gwt-jiaowu",            // 触发推送的源
 *   "count": 5,
 *   "latestIdBefore": "51182",           // 推送前频道 latestId
 *   "latestIdAfter":  "51187",
 *   "wsSubscribers":  1,
 *   "items": [
 *     {"id":"51187","title":"...","publishDate":"2026-05-02","crawledAt":1714660201123,"sourceId":"gwt-jiaowu","isStale":false}
 *   ],
 *   "anyStale": false                    // 整体是否含 stale item，方便 grep
 * }
 * </pre>
 * <p>
 * <b>isStale 判定</b>：item.id 数值上 <= latestIdBefore 视为陈旧推送（应被 filter 掉却没被）。
 * 非数字 ID（acdm-* 系）走 hasMeta 单独判：已存在 → isStale。
 * <p>
 * 文件：{@code infos/runtime-trace/ws-pushes.jsonl}（追加，需手工或外部 logrotate 处理体积）
 * <p>
 * <b>开关</b>：
 * <pre>
 * diagnostics:
 *   ws-push-log:
 *     enabled: true                    # 默认开（IO 成本低，每次推送 1 行）
 *     path: infos/runtime-trace/ws-pushes.jsonl
 *     log-stale-only: false            # true 则只记 anyStale=true 的 push
 * </pre>
 */
@Slf4j
@Component
public class WsPushLog {

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private WsSessionRegistry wsSessionRegistry;

    @Value("${diagnostics.ws-push-log.enabled:true}")
    private boolean enabled;

    @Value("${diagnostics.ws-push-log.path:infos/runtime-trace/ws-pushes.jsonl}")
    private String path;

    @Value("${diagnostics.ws-push-log.log-stale-only:false}")
    private boolean logStaleOnly;

    /**
     * 记录一次推送。在 broadcastNewContent 调 streamPublisher.publishToAll **之前** 调。
     *
     * @param trigger        触发路径标签，如 "crawlIncremental" / "initSource-phase1" / "initSource-phase2"
     * @param channelId      频道
     * @param sourceId       触发的源 id（可空）
     * @param items          要推的 items
     * @param latestIdBefore 推送前的 latestId（infoCacheUtil.getLatestId）
     * @param latestIdAfter  推送后的 latestId
     */
    public void record(String trigger, String channelId, String sourceId,
                       List<InfoItemMeta> items,
                       String latestIdBefore, String latestIdAfter) {
        if (!enabled || items == null || items.isEmpty()) return;

        try {
            JSONObject root = new JSONObject();
            root.put("ts", Instant.now().toString());
            root.put("trigger", trigger);
            root.put("channelId", channelId);
            root.put("sourceId", sourceId);
            root.put("count", items.size());
            root.put("latestIdBefore", latestIdBefore);
            root.put("latestIdAfter", latestIdAfter);
            root.put("wsSubscribers", wsSessionRegistry.getConnectionCount(channelId));

            Long beforeNum = parseLongSafe(latestIdBefore);

            JSONArray arr = new JSONArray();
            boolean anyStale = false;
            for (InfoItemMeta meta : items) {
                JSONObject it = new JSONObject();
                it.put("id", meta.getId());
                it.put("title", truncate(meta.getTitle(), 80));
                it.put("publishDate", meta.getPublishDate());
                it.put("crawledAt", meta.getCrawledAt());
                it.put("sourceId", meta.getSourceId());

                boolean stale = isStale(meta, channelId, beforeNum);
                it.put("isStale", stale);
                if (stale) anyStale = true;
                arr.add(it);
            }
            root.put("items", arr);
            root.put("anyStale", anyStale);

            if (logStaleOnly && !anyStale) return;

            writeLine(root.toJSONString());

            if (anyStale) {
                log.warn("[ws-push] STALE detected: trigger={} channel={} count={} latestIdBefore={}",
                        trigger, channelId, items.size(), latestIdBefore);
            }
        } catch (Exception e) {
            log.warn("[ws-push] record failed: {}", e.getMessage());
        }
    }

    /**
     * isStale 判定：
     * - 数字 ID 频道：item.id <= latestIdBefore → stale
     * - 非数字 ID 频道（latestIdBefore 非数字 / item.id 非数字）：用 hasMeta 检查；
     *   已在 meta hash 里 → 已存在 → stale
     */
    private boolean isStale(InfoItemMeta meta, String channelId, Long latestIdBefore) {
        Long idNum = parseLongSafe(meta.getId());
        if (latestIdBefore != null && idNum != null) {
            return idNum <= latestIdBefore;
        }
        // 非数字路径：用 hasMeta 检查（推送前已存在 = 陈旧）
        // 但注意：本次推送已经 saveMeta 过，hasMeta 现在也返 true。需要在 record 调用顺序上
        // 保证 record 在 saveMeta 之前调，否则全部判 stale。
        // CrawlEngine 当前流程是：filterNewItems → saveMetaBatch → broadcastNewContent。
        // record 在 broadcastNewContent 内，saveMetaBatch 已发生 → hasMeta 必然 true。
        // 因此这条路径暂时 fallback 为 false，只用数字 ID 路径判 stale。
        // TODO: 如果 acdm-* 频道也想检测 stale，需要在 CrawlEngine 把"saveMeta 之前的 hasMeta 判定"
        // 透传给 WsPushLog.record。本次先简化。
        return false;
    }

    private static Long parseLongSafe(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void writeLine(String json) throws IOException {
        Path p = Paths.get(path);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        try (var w = Files.newBufferedWriter(p,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(json);
            w.newLine();
        }
    }
}
