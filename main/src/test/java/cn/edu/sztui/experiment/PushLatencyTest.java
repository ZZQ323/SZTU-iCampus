package cn.edu.sztui.experiment;

import cn.edu.sztui.base.BaseMain;
import cn.edu.sztui.stream.application.service.StreamPushService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * 实验 2.1：WS 端到端推送延迟分布
 * <p>
 * 验证论文 §3 章 "端到端 100ms 内" 的承诺。
 * <p>
 * 流程：
 * <ol>
 *   <li>启动 SpringBoot 完整 app（{@code @LocalServerPort} 拿到本机端口）</li>
 *   <li>用 {@link StandardWebSocketClient} 连 {@code ws://localhost:<port>/ws?userId=test-latency&topics=announcement}</li>
 *   <li>等连接握手完成 + 订阅生效（sleep 短暂时间）</li>
 *   <li>循环 N 次直接调 {@link StreamPushService#broadcastAnnouncement(Object)}，
 *       payload 是 {@code {sentAtMs: <epoch>, seq: <i>}}</li>
 *   <li>客户端 onMessage 解析 {@code sentAtMs}，计算 {@code recvAtMs - sentAtMs} → latency 列表</li>
 *   <li>等所有消息到达（CountDownLatch + 超时），输出 p50/p95/p99</li>
 * </ol>
 * <p>
 * <b>诚实声明（论文里要写）</b>：测试客户端与后端在同一 JVM、同一进程；网络栈是
 * loopback。延迟主要是 Redis Pub/Sub + Stream pipeline + WS 框架开销，<b>不</b>包含
 * 公网 RTT 或小程序客户端处理时间。论文里要标注 "本机环境"。
 * <p>
 * 配置（命令行 -D 覆盖）：
 * <ul>
 *   <li>{@code -Dexp21.count=1000}    总消息数（默认 1000）</li>
 *   <li>{@code -Dexp21.intervalMs=2}  推送间隔毫秒（默认 2，避免 burst 打爆 listener）</li>
 *   <li>{@code -Dexp21.timeoutSec=60} 等所有消息到达的超时（默认 60s）</li>
 * </ul>
 * <p>
 * 运行：{@code ./gradlew :main:test --tests PushLatencyTest --info}
 */
@SpringBootTest(classes = BaseMain.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("experiment")
class PushLatencyTest {

    @LocalServerPort
    private int port;

    @Resource
    private StreamPushService streamPushService;

    @Test
    void pushLatencyDistribution() throws Exception {
        int count = Integer.getInteger("exp21.count", 1000);
        long intervalMs = Long.parseLong(System.getProperty("exp21.intervalMs", "2"));
        int timeoutSec = Integer.getInteger("exp21.timeoutSec", 60);

        URI wsUri = URI.create("ws://localhost:" + port + "/ws?userId=test-latency&topics=announcement");

        CountDownLatch arrived = new CountDownLatch(count);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(count));
        AtomicIntegerWrapper recvCount = new AtomicIntegerWrapper();

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession s) {
                System.out.println("[exp21] WS connected: " + s.getId());
            }

            @Override
            public void handleMessage(WebSocketSession s, WebSocketMessage<?> message) {
                long recvAt = System.currentTimeMillis();
                if (!(message instanceof TextMessage tm)) return;
                try {
                    JSONObject root = JSON.parseObject(tm.getPayload());
                    // WsMessage 结构：{type, data: {...}, ...}
                    JSONObject data = root.getJSONObject("data");
                    if (data == null) return;
                    Long sentAtMs = data.getLong("sentAtMs");
                    if (sentAtMs == null) return;
                    long latency = recvAt - sentAtMs;
                    latencies.add(latency);
                    recvCount.inc();
                    arrived.countDown();
                } catch (Exception ignore) {}
            }

            @Override
            public void handleTransportError(WebSocketSession s, Throwable e) {
                System.err.println("[exp21] WS error: " + e.getMessage());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                System.out.println("[exp21] WS closed: " + status);
            }

            @Override public boolean supportsPartialMessages() { return false; }
        }, null, wsUri).get(15, TimeUnit.SECONDS);

        // 等订阅生效（WsSessionRegistry 注册需要时间）
        Thread.sleep(500);

        long pushStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Map<String, Object> payload = Map.of(
                    "sentAtMs", System.currentTimeMillis(),
                    "seq", i
            );
            streamPushService.broadcastAnnouncement(payload);
            if (intervalMs > 0) Thread.sleep(intervalMs);
        }
        long pushEnd = System.currentTimeMillis();
        System.out.printf("[exp21] %d messages published in %d ms (rate ~ %.1f msg/s)%n",
                count, pushEnd - pushStart, 1000.0 * count / Math.max(1, pushEnd - pushStart));

        boolean allArrived = arrived.await(timeoutSec, TimeUnit.SECONDS);
        session.close();

        // 输出报告
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(80)).append("\n");
        out.append("实验 2.1  WS 端到端推送延迟分布（loopback / 本机进程内）\n");
        out.append("=".repeat(80)).append("\n");
        out.append(String.format("发送: %d 条   接收: %d 条   全部到达: %s%n",
                count, recvCount.get(), allArrived));
        if (sorted.isEmpty()) {
            out.append("无样本可用，可能 WS 订阅未生效\n");
        } else {
            out.append(String.format("min   = %d ms%n", sorted.get(0)));
            out.append(String.format("p50   = %d ms%n", percentile(sorted, 50)));
            out.append(String.format("p90   = %d ms%n", percentile(sorted, 90)));
            out.append(String.format("p95   = %d ms%n", percentile(sorted, 95)));
            out.append(String.format("p99   = %d ms%n", percentile(sorted, 99)));
            out.append(String.format("max   = %d ms%n", sorted.get(sorted.size() - 1)));
            out.append(String.format("mean  = %.1f ms%n",
                    sorted.stream().mapToLong(Long::longValue).average().orElse(0)));
            long under100 = sorted.stream().filter(v -> v <= 100).count();
            out.append(String.format("≤100ms = %d / %d = %.2f%%   ←  论文 §3 承诺值%n",
                    under100, sorted.size(), 100.0 * under100 / sorted.size()));
            long under500 = sorted.stream().filter(v -> v <= 500).count();
            out.append(String.format("≤500ms = %d / %d = %.2f%%%n",
                    under500, sorted.size(), 100.0 * under500 / sorted.size()));
        }
        out.append("=".repeat(80)).append("\n");
        System.out.println(out);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** 简单线程安全计数器（避免引入 AtomicInteger 包装好看一点的 import） */
    private static class AtomicIntegerWrapper {
        private final java.util.concurrent.atomic.AtomicInteger v = new java.util.concurrent.atomic.AtomicInteger();
        void inc() { v.incrementAndGet(); }
        int get() { return v.get(); }
    }
}
