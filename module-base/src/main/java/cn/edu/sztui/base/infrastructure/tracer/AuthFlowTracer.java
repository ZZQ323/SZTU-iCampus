package cn.edu.sztui.base.infrastructure.tracer;

import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 认证全流程追踪器
 * <p>
 * 配置 {@code auth.trace.enabled=true} 时打开。每次 AuthServiceImpl 的关键 API
 * （initSession / sendSms / login / logout / refreshSession）都会落盘：
 * <ul>
 *   <li>{@code _summary.txt} —— 操作概要</li>
 *   <li>{@code hop-NNN_<label>_cookies.json} —— 该跳后的 SmartSession cookies 全集</li>
 *   <li>{@code hop-NNN_<label>_resp.html} —— 该跳响应（status + finalUrl + body 前 8KB）</li>
 * </ul>
 * <p>
 * 所有 log 行用 <b>[AUTH-TRACE]</b> 前缀，方便 grep。
 * <p>
 * 输出位置：{@code infos/runtime-trace/auth-flow/<时间戳>_<stage>_<userId|anon>/}
 */
@Slf4j
@Component
public class AuthFlowTracer {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Value("${auth.trace.enabled:false}")
    private boolean enabled;

    @Value("${auth.trace.dir:infos/runtime-trace/auth-flow}")
    private String baseDir;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 开启一次操作。返回 trace 目录（关闭时返 null）。
     */
    public Path startOp(String stage, String userId) {
        if (!enabled) return null;
        String ts = LocalDateTime.now().format(TS_FMT);
        String uid = userId == null || userId.isBlank() ? "anon" : userId;
        Path dir = Paths.get(baseDir, ts + "_" + stage + "_" + uid);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[AUTH-TRACE] mkdir fail: {} err={}", dir, e.getMessage());
            return null;
        }
        log.info("[AUTH-TRACE] OP-START stage={} userId={} dir={}", stage, uid, dir.toAbsolutePath());
        return dir;
    }

    /**
     * 落盘一跳（请求结束后调）。
     *
     * @param dir       startOp 返回的目录；null 表示 trace 关
     * @param hopNum    跳号（自增，由调用方维护）
     * @param label     操作标签，如 "init-gateway-fetch" / "sms-postAjax" / "login-postAjax"
     * @param session   当前 SmartSession（cookies 在这里）
     * @param response  本跳响应（可 null）
     */
    public void dumpHop(Path dir, int hopNum, String label, SmartSession session, SmartResponse response) {
        if (dir == null || !enabled) return;
        try {
            String prefix = String.format("hop-%03d_%s", hopNum, sanitize(label));

            // cookies after
            if (session != null && session.getCookies() != null) {
                String cookiesJson = JSON.toJSONString(session.getCookies(), JSONWriter.Feature.PrettyFormat);
                Files.writeString(dir.resolve(prefix + "_cookies.json"), cookiesJson, StandardCharsets.UTF_8);
            }

            // response
            if (response != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("STATUS:      ").append(response.getStatusCode()).append('\n');
                sb.append("FINAL_URL:   ").append(response.getFinalUrl()).append('\n');
                sb.append("REDIRECTS:   ").append(response.getRedirectCount()).append('\n');
                sb.append("\n").append("=".repeat(80)).append("\n\n");
                String body = response.getBody();
                if (body != null) {
                    sb.append(body.length() > 8192 ? body.substring(0, 8192) + "\n\n... [TRUNCATED " + (body.length() - 8192) + " more chars]" : body);
                }
                Files.writeString(dir.resolve(prefix + "_resp.html"), sb.toString(), StandardCharsets.UTF_8);
            }

            int n = session == null ? -1 : session.getCookies().size();
            int status = response == null ? -1 : response.getStatusCode();
            String finalUrl = response == null ? "?" : response.getFinalUrl();
            log.info("[AUTH-TRACE] HOP-{} {} cookies={} status={} finalUrl={}", hopNum, label, n, status, finalUrl);
        } catch (IOException e) {
            log.warn("[AUTH-TRACE] dump fail: hop={} label={} err={}", hopNum, label, e.getMessage());
        }
    }

    /**
     * 操作结束。写 _summary.txt 总结。
     */
    public void finishOp(Path dir, String summary) {
        if (dir == null || !enabled) return;
        try {
            Files.writeString(dir.resolve("_summary.txt"), summary, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        log.info("[AUTH-TRACE] OP-DONE dir={} summary={}", dir.getFileName(), summary);
    }

    /**
     * 直接 dump 一段任意 cookies（如前端传来的、Redis 里读到的），方便对比。
     */
    public void dumpAuxCookies(Path dir, String label, String cookiesJson) {
        if (dir == null || !enabled || cookiesJson == null) return;
        try {
            Files.writeString(dir.resolve("aux_" + sanitize(label) + ".json"), cookiesJson, StandardCharsets.UTF_8);
            log.info("[AUTH-TRACE] AUX {} length={}", label, cookiesJson.length());
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "anon";
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
