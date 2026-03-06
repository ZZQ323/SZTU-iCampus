package cn.edu.sztui.common.util.smarthttp.redirect;

import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebVPN Portal 入口页面重定向处理器
 * 
 * 处理 /por/ 或根路径页面的重定向：
 * 
 * URL 模式：
 * - https://webvpn.sztu.edu.cn/por/?redirect_uri=...
 * - https://webvpn.sztu.edu.cn/?redirect_uri=...
 * 
 * 这些页面会通过 JS 重定向到 /public/thdportal_login
 * 
 * 如果 body 中检测到需要登录，直接跳转到 thdportal_login
 */
@Slf4j
public class PortalEntryRedirectHandler implements RedirectHandler {
    
    /**
     * 匹配 webvpn 入口 URL（/por/ 或根路径 + redirect_uri）
     */
    private static final Pattern POR_URL_PATTERN = Pattern.compile(
            "webvpn\\.sztu\\.edu\\.cn(/por)?/?\\?redirect_uri="
    );
    
    /**
     * 从 URL 中提取 redirect_uri 参数
     */
    private static final Pattern REDIRECT_URI_PATTERN = Pattern.compile(
            "[?&]redirect_uri=([^&]+)"
    );
    
    /**
     * 匹配 JS 中的 window.location 重定向
     */
    private static final Pattern JS_LOCATION_PATTERN = Pattern.compile(
            "window\\.location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']"
    );
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 200 状态码
        if (statusCode != 200) {
            return null;
        }
        
        // 检查是否是 /por/ 或根路径 + redirect_uri 的 URL
        if (!POR_URL_PATTERN.matcher(currentUrl).find()) {
            return null;
        }
        
        log.debug("[PortalEntry] 检测到 WebVPN 入口页面: {}", currentUrl);
        
        // 如果 body 包含 g_lines，让 GLinesRedirectHandler 处理
        if (body != null && body.contains("g_lines") && body.contains("gotoLines")) {
            log.debug("[PortalEntry] 包含 g_lines，交给 GLinesRedirectHandler 处理");
            return null;
        }
        
        // 尝试从 body 中提取 JS 重定向
        if (body != null) {
            Matcher jsMatcher = JS_LOCATION_PATTERN.matcher(body);
            if (jsMatcher.find()) {
                String jsUrl = jsMatcher.group(1);
                if (jsUrl.startsWith("http")) {
                    log.debug("[PortalEntry] 从 JS 中提取到重定向 URL: {}", jsUrl);
                    return jsUrl;
                }
            }
        }
        
        // 检查是否包含登录页面的标识
        if (body != null && (body.contains("/portal/#!/login") || body.contains("#!/login") 
                || body.contains("avalon.state(\"login\")"))) {
            // 提取 redirect_uri
            Matcher matcher = REDIRECT_URI_PATTERN.matcher(currentUrl);
            if (matcher.find()) {
                String redirectUri = matcher.group(1);
                try {
                    redirectUri = URLDecoder.decode(redirectUri, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                
                // 构建 thdportal_login URL
                String encodedUri = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
                String loginUrl = "https://webvpn.sztu.edu.cn/public/thdportal_login?redirect_uri=" + encodedUri;
                
                log.debug("[PortalEntry] 检测到登录页面，重定向到: {}", loginUrl);
                return loginUrl;
            }
        }
        
        return null;
    }
    
    @Override
    public String getName() {
        return "PortalEntry";
    }
    
    @Override
    public int getPriority() {
        return 8; // 在 GLinesRedirectHandler (10) 之前
    }
}
