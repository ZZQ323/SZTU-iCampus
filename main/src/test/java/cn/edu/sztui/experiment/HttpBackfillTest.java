package cn.edu.sztui.experiment;

import cn.edu.sztui.base.BaseMain;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.stream.application.service.StreamPushService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 实验 2.2：双通道架构 —— HTTP 兜底拉取的正确性
 * <p>
 * 论文 §3 描述的"双通道"架构：
 * <ul>
 *   <li>通道 1（实时）：WebSocket 推送，在线时收到流式事件</li>
 *   <li>通道 2（兜底）：HTTP REST {@code /info/v1/list}，重连后主动拉缺失</li>
 * </ul>
 * 两条通路互补：WS 漏的，HTTP 拉得回（数据都在 Redis 里）。本测试验证此承诺。
 * <p>
 * 测试流程：
 * <ol>
 *   <li>客户端 A 连 WS 订阅 announcement</li>
 *   <li>记录当前频道 latestId（B 断开点）</li>
 *   <li>"客户端 B" 不连 WS，模拟离线</li>
 *   <li>后端通过 {@link InfoCacheUtil#saveMeta} 写入 N 条新条目 +
 *       {@link StreamPushService#broadcastAnnouncement} 推送 → A 收到 N 条</li>
 *   <li>B 重连后立即 HTTP GET {@code /info/v1/list?channelId=announcement&pageSize=N+10}</li>
 *   <li>过滤出 id > 断开点的条目（这正是 B 错过的）</li>
 *   <li>验证 B 拿到的 ID 集合 == A 收到的 ID 集合</li>
 * </ol>
 * <p>
 * 指标：
 * <ul>
 *   <li>补齐完整率 = 实际拿到条数 / 应拿到条数（期望 100%）</li>
 *   <li>补齐延迟  = 重连后到 HTTP 拉取完成的时间</li>
 * </ul>
 * <p>
 * 配置：
 * <ul>
 *   <li>{@code -Dexp22.count=50}      推送条数（默认 50）</li>
 *   <li>{@code -Dexp22.intervalMs=20} 推送间隔（默认 20，给 WS broadcast 时间）</li>
 * </ul>
 */
@SpringBootTest(classes = BaseMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("experiment")
class HttpBackfillTest {

    @LocalServerPort
    private int port;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private StreamPushService streamPushService;

    @Resource
    private CacheUtil cacheUtil;

    private static final String CHANNEL = "announcement";

    @Test
    void httpBackfillCorrectness() throws Exception {
        int count = Integer.getInteger("exp22.count", 50);
        long intervalMs = Long.parseLong(System.getProperty("exp22.intervalMs", "20"));

        // ==================== 1. 客户端 A 连 WS 订阅 ====================
        Set<String> aReceivedIds = ConcurrentHashMap.newKeySet();
        URI wsUri = URI.create("ws://localhost:" + port + "/ws?userId=test-backfill-A&topics=" + CHANNEL);
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        WebSocketSession sessionA = wsClient.execute(new ReceivingHandler(aReceivedIds), null, wsUri).get(15, TimeUnit.SECONDS);
        Thread.sleep(300);  // 等订阅生效

        // ==================== 2. 记录 B 断开点 ====================
        String lastIdBeforeB = infoCacheUtil.getLatestId(CHANNEL);
        System.out.println("[exp22] B 断开点 latestId=" + lastIdBeforeB);

        // ==================== 3. 后端推 N 条 ====================
        // 用 currentTimeMillis * 1000 + i 作为 ID，确保比所有现有 ID 都大
        long base = System.currentTimeMillis() * 1000L;
        List<String> publishedIds = new ArrayList<>(count);
        try {
        for (int i = 0; i < count; i++) {
            String id = String.valueOf(base + i);
            InfoItemMeta meta = InfoItemMeta.builder()
                    .id(id)
                    .url("https://example.com/test/" + id)
                    .title("[exp22] 测试条目 #" + i + " seq=" + System.currentTimeMillis())
                    .channelId(CHANNEL)
                    .sourceId("__exp22__")
                    .sourceOrg("test")
                    .sourceOrgName("实验 2.2")
                    .contentType("notice")
                    .subContentType("general-notice")
                    .crawledAt(System.currentTimeMillis())
                    .publishDate(java.time.LocalDate.now().toString())
                    .build();
            infoCacheUtil.saveMeta(CHANNEL, meta);
            publishedIds.add(id);
            // 也走 WS 推送，A 应该能收到
            streamPushService.broadcastAnnouncement(Map.of("items", List.of(meta)));
            if (intervalMs > 0) Thread.sleep(intervalMs);
        }
        // 给 A 的 WS 时间收完
        Thread.sleep(1000);

        // ==================== 4. 验证 A 收到 N 条 ====================
        Set<String> publishedSet = new HashSet<>(publishedIds);
        int aReceivedFromBatch = 0;
        for (String id : aReceivedIds) {
            if (publishedSet.contains(id)) aReceivedFromBatch++;
        }

        // ==================== 5. B 重连 + HTTP 兜底拉取 ====================
        // 真实场景下 B 也连 WS，但本实验只测 HTTP 兜底正确性，跳过 WS 重连
        long httpStart = System.currentTimeMillis();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // pageSize 取 count + 缓冲，防止刚好踩边界
        String url = "http://localhost:" + port
                + "/info/v1/list?channelId=" + CHANNEL + "&page=1&pageSize=" + (count + 20);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-User-Id", "test-backfill-B")
                .header("X-School-Cookies", "[]")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long httpEnd = System.currentTimeMillis();

        // ==================== 6. 过滤 B 实际"应该补回的"条目 ====================
        Set<String> bRecoveredIds = new HashSet<>();
        if (resp.statusCode() == 200) {
            JSONObject body = JSON.parseObject(resp.body());
            JSONObject data = body.getJSONObject("data");
            if (data != null) {
                JSONArray items = data.getJSONArray("items");
                if (items != null) {
                    long lastIdNum = parseLongSafe(lastIdBeforeB);
                    for (int i = 0; i < items.size(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String id = item.getString("id");
                        if (id == null) continue;
                        long idNum = parseLongSafe(id);
                        // 过滤：只保留 id > 断开点 的（B 错过的部分）
                        if (lastIdBeforeB == null || idNum > lastIdNum) {
                            bRecoveredIds.add(id);
                        }
                    }
                }
            }
        }

        // ==================== 7. 比对集合 ====================
        Set<String> intersection = new HashSet<>(bRecoveredIds);
        intersection.retainAll(publishedSet);
        Set<String> missing = new HashSet<>(publishedSet);
        missing.removeAll(bRecoveredIds);
        Set<String> extra = new HashSet<>(bRecoveredIds);
        extra.removeAll(publishedSet);

        sessionA.close();

        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(80)).append("\n");
        out.append("实验 2.2  HTTP 兜底拉取的正确性验证\n");
        out.append("=".repeat(80)).append("\n");
        out.append(String.format("推送条数: %d   B 断开点 lastId=%s%n", count, lastIdBeforeB));
        out.append(String.format("HTTP 状态: %d%n", resp.statusCode()));
        out.append("\n--- A（在线）通过 WS 收到 ---\n");
        out.append(String.format("收到本批次 ID: %d / %d   总收到: %d%n",
                aReceivedFromBatch, count, aReceivedIds.size()));
        out.append("\n--- B（重连后）通过 HTTP 兜底拉取 ---\n");
        out.append(String.format("拉到 lastId 之后的 ID 数: %d%n", bRecoveredIds.size()));
        out.append(String.format("与 publishedIds 交集: %d%n", intersection.size()));
        out.append(String.format("missing（应拿到但没拿到）: %d%n", missing.size()));
        out.append(String.format("extra（多拿到的，可能是真实其他源数据，可忽略）: %d%n", extra.size()));
        out.append(String.format("补齐延迟（HTTP 单次 RTT）: %d ms%n", httpEnd - httpStart));
        double recoveryRate = count == 0 ? 0 : 100.0 * intersection.size() / count;
        out.append(String.format("【补齐完整率】 %d / %d = %.2f%%%n", intersection.size(), count, recoveryRate));
        if (!missing.isEmpty()) {
            out.append("missing 样例: " + missing.stream().limit(5).toList() + "\n");
        }
        out.append("=".repeat(80)).append("\n");
        System.out.println(out);
        } finally {
            // ==================== 8. 清理：删掉所有测试数据，避免污染前端信息流 ====================
            cleanup(publishedIds);
        }
    }

    /**
     * 清理本测试写入 Redis 的所有数据。
     * <p>
     * {@link InfoCacheUtil#saveMeta} 写到 4 处：
     * <ul>
     *   <li>Hash {@code info:announcement:meta} → field=articleId</li>
     *   <li>ZSET {@code info:announcement:timeline} → member=articleId</li>
     *   <li>ZSET {@code feed:timeline} → member="announcement:" + articleId</li>
     *   <li>String {@code feed:meta:announcement:{id}}</li>
     * </ul>
     * 全部清掉，前端信息流恢复原状。category ZSET 没写（构造 meta 时未设 categoryCode）。
     */
    private void cleanup(List<String> publishedIds) {
        if (publishedIds == null || publishedIds.isEmpty()) return;
        int n = publishedIds.size();
        for (String id : publishedIds) {
            try {
                cacheUtil.hdel("info:" + CHANNEL + ":meta", id);
                cacheUtil.zRem("info:" + CHANNEL + ":timeline", id);
                cacheUtil.zRem("feed:timeline", CHANNEL + ":" + id);
                cacheUtil.del("feed:meta:" + CHANNEL + ":" + id);
            } catch (Exception e) {
                System.err.println("[exp22 cleanup] failed for id=" + id + ": " + e.getMessage());
            }
        }
        System.out.println("[exp22 cleanup] removed " + n + " test entries from Redis");
    }

    private static long parseLongSafe(String s) {
        if (s == null) return Long.MIN_VALUE;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return Long.MIN_VALUE; }
    }

    /** WS 接收处理器：把每条 message data.items[*].id 加入集合 */
    private static class ReceivingHandler implements WebSocketHandler {
        private final Set<String> sink;
        ReceivingHandler(Set<String> sink) { this.sink = sink; }

        @Override public void afterConnectionEstablished(WebSocketSession session) {}

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (!(message instanceof TextMessage tm)) return;
            try {
                JSONObject root = JSON.parseObject(tm.getPayload());
                JSONObject data = root.getJSONObject("data");
                if (data == null) return;
                // payload 可能是 {items:[meta,...]} 或单个 meta
                JSONArray items = data.getJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.size(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (item == null) continue;
                        String id = item.getString("id");
                        if (id != null) sink.add(id);
                    }
                } else {
                    String id = data.getString("id");
                    if (id != null) sink.add(id);
                }
            } catch (Exception ignore) {}
        }

        @Override public void handleTransportError(WebSocketSession s, Throwable e) {}
        @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {}
        @Override public boolean supportsPartialMessages() { return false; }
    }
}
