package cn.edu.sztui.common.util.smarthttp.redirect;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * g_lines 重定向处理器（学校 WebVPN 网关特定）
 * 
 * 处理学校网关的 JS 选路重定向：
 * <script>
 *   g_lines = [{src:"", url:"https://webvpn.sztu.edu.cn/public/...", right:0}];
 *   gotoLines();
 * </script>
 * 
 * 核心逻辑：提取 g_lines 数组中第一个对象的 url 属性
 */
@Slf4j
public class GLinesRedirectHandler implements RedirectHandler {
    
    /**
     * 匹配 g_lines 数组定义
     * 
     * 示例：
     * g_lines = [{src:"",url:"https://webvpn.sztu.edu.cn/public/thdportal_login?...",right:0}];
     */
    private static final Pattern G_LINES_PATTERN = Pattern.compile(
            "g_lines\\s*=\\s*\\[\\s*\\{[^}]*url\\s*:\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );
    
    /**
     * 匹配 gotoLines() 或 win_location 调用，确认是选路页面
     */
    private static final Pattern GOTO_LINES_PATTERN = Pattern.compile(
            "(gotoLines\\s*\\(\\s*\\)|win_location)",
            Pattern.CASE_INSENSITIVE
    );
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 200 状态码
        if (statusCode != 200 || body == null || body.isEmpty()) {
            return null;
        }
        
        // 快速检查：是否包含关键标识
        if (!body.contains("g_lines") || !body.contains("gotoLines")) {
            return null;
        }
        
        // 确认是选路页面
        Matcher gotoMatcher = GOTO_LINES_PATTERN.matcher(body);
        if (!gotoMatcher.find()) {
            return null;
        }
        
        // 提取 URL
        Matcher matcher = G_LINES_PATTERN.matcher(body);
        if (matcher.find()) {
            String url = matcher.group(1).trim();
            
            // 验证 URL 格式
            if (url.startsWith("http://") || url.startsWith("https://")) {
                log.debug("[GLinesRedirect] {} -> {}", currentUrl, url);
                return url;
            }
        }
        
        return null;
    }
    
    @Override
    public String getName() {
        return "GLinesRedirect";
    }
    
    @Override
    public int getPriority() {
        return 10; // JS 重定向优先级较低
    }
}
