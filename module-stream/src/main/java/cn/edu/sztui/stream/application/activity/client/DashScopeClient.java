package cn.edu.sztui.stream.application.activity.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DashScope（OpenAI 兼容模式）聊天接口客户端。
 * <p>
 * 专用于后台结构化抽取场景：
 * <ul>
 *   <li><b>stream=false</b> —— 一次性拿完整响应，不用处理 SSE 分块</li>
 *   <li><b>enable_thinking=false</b> —— 抽取任务不需要 CoT，省 token + 提速</li>
 *   <li><b>response_format=json_object</b> —— 强制 JSON 输出，后端解析稳定</li>
 * </ul>
 * <p>
 * 返回的是 {@code choices[0].message.content}（一个 JSON 字符串），调用方自行反序列化成业务对象。
 */
@Slf4j
@Component
public class DashScopeClient {

    @Value("${ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${ai.dashscope.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String endpoint;

    @Value("${ai.dashscope.model:qwen-turbo}")
    @Getter
    private String model;

    @Value("${ai.dashscope.timeout-seconds:30}")
    private int timeoutSeconds;

    private RestClient restClient;

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * 调用 /chat/completions，返回 {@link ChatResult}。JSON 解析由调用方做。
     *
     * @param systemPrompt  system role 的 prompt（不可空）
     * @param userContent   user role 的内容（实际文章或上下文）
     * @return 结果，失败时 {@link ChatResult#content} 为 null，{@link ChatResult#error} 说明原因
     */
    public ChatResult chatJson(String systemPrompt, String userContent) {
        if (!isConfigured()) {
            return ChatResult.fail("DashScope API Key 未配置（ai.dashscope.api-key）");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                ),
                "stream", false,
                "enable_thinking", false,
                "response_format", Map.of("type", "json_object")
        );

        long start = System.currentTimeMillis();
        try {
            String respBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(JSON.toJSONString(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new RuntimeException("DashScope error status: " + resp.getStatusCode() +
                                ", body: " + new String(resp.getBody().readAllBytes()));
                    })
                    .body(String.class);

            long elapsed = System.currentTimeMillis() - start;
            return parseResponse(respBody, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[DashScope] call failed after {}ms: {}", elapsed, e.getMessage());
            ChatResult r = ChatResult.fail(e.getMessage());
            r.durationMs = elapsed;
            return r;
        }
    }

    private ChatResult parseResponse(String respBody, long elapsed) {
        if (!StringUtils.hasText(respBody)) {
            return ChatResult.fail("empty response");
        }
        try {
            JSONObject root = JSON.parseObject(respBody);
            JSONObject choice = root.getJSONArray("choices").getJSONObject(0);
            String content = choice.getJSONObject("message").getString("content");

            ChatResult r = new ChatResult();
            r.content = content;
            r.durationMs = elapsed;

            JSONObject usage = root.getJSONObject("usage");
            if (usage != null) {
                r.promptTokens = usage.getInteger("prompt_tokens");
                r.completionTokens = usage.getInteger("completion_tokens");
            }
            return r;
        } catch (Exception e) {
            log.warn("[DashScope] response parse failed: {}", e.getMessage());
            return ChatResult.fail("parse error: " + e.getMessage());
        }
    }

    // ==================== 结果 ====================

    @Getter
    public static class ChatResult {
        /** AI 返回的原始 content（通常是 JSON 字符串），失败时 null */
        private String content;
        /** 耗时 */
        private long durationMs;
        private Integer promptTokens;
        private Integer completionTokens;
        /** 错误信息，成功时 null */
        private String error;

        public boolean isOk() {
            return content != null && error == null;
        }

        static ChatResult fail(String err) {
            ChatResult r = new ChatResult();
            r.error = err;
            return r;
        }
    }
}
