package cn.edu.sztui.common.util.smarthttp.redirect;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用 JS 重定向处理器
 * 
 * 使用正则匹配常见的 JS 重定向模式
 */
@Slf4j
public class JsRedirectHandler implements RedirectHandler {
    
    /**
     * 常见的 JS 重定向模式
     */
    private static final List<Pattern> JS_REDIRECT_PATTERNS = List.of(
            // window.location.href = "url"
            Pattern.compile("window\\.location\\.href\\s*=\\s*[\"']([^\"']+)[\"']"),
            // window.location = "url"
            Pattern.compile("window\\.location\\s*=\\s*[\"']([^\"']+)[\"']"),
            // location.href = "url"
            Pattern.compile("(?<![.])location\\.href\\s*=\\s*[\"']([^\"']+)[\"']"),
            // location.replace("url")
            Pattern.compile("location\\.replace\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"),
            // top.location = "url"
            Pattern.compile("top\\.location\\s*=\\s*[\"']([^\"']+)[\"']"),
            // self.location = "url"
            Pattern.compile("self\\.location\\s*=\\s*[\"']([^\"']+)[\"']"),
            // document.location = "url"
            Pattern.compile("document\\.location\\s*=\\s*[\"']([^\"']+)[\"']"),
            // document.location.href = "url"
            Pattern.compile("document\\.location\\.href\\s*=\\s*[\"']([^\"']+)[\"']")
    );
    
    /**
     * 应该跳过的模式（占位符、模板变量等）
     */
    private static final List<String> SKIP_PATTERNS = List.of(
            "${", "{{", "}}", "<%", "%>", // 模板变量
            "javascript:", // 伪协议
            "'+", "\" +", // 字符串拼接
            "?'+", "?\" +" // 动态参数拼接
    );
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 200 状态码
        if (statusCode != 200 || body == null || body.isEmpty()) {
            return null;
        }
        
        // 快速检查：是否包含 location
        if (!body.contains("location")) {
            return null;
        }
        
        // 提取 <script> 标签内容
        String scriptContent = extractScriptContent(body);
        if (scriptContent == null || scriptContent.isEmpty()) {
            return null;
        }
        
        // 尝试所有模式
        for (Pattern pattern : JS_REDIRECT_PATTERNS) {
            Matcher matcher = pattern.matcher(scriptContent);
            if (matcher.find()) {
                String url = matcher.group(1).trim();
                
                // 检查是否应该跳过
                if (shouldSkip(url)) {
                    continue;
                }
                
                // 解析 URL
                String resolvedUrl = resolveUrl(currentUrl, url);
                if (resolvedUrl != null) {
                    log.debug("[JsRedirect] {} -> {}", currentUrl, resolvedUrl);
                    return resolvedUrl;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 提取 script 标签内容
     */
    private String extractScriptContent(String html) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        
        while (true) {
            int scriptStart = html.indexOf("<script", start);
            if (scriptStart == -1) break;
            
            int contentStart = html.indexOf(">", scriptStart);
            if (contentStart == -1) break;
            contentStart++;
            
            int scriptEnd = html.indexOf("</script>", contentStart);
            if (scriptEnd == -1) break;
            
            sb.append(html, contentStart, scriptEnd).append("\n");
            start = scriptEnd + 9;
        }
        
        return sb.toString();
    }
    
    /**
     * 检查是否应该跳过
     */
    private boolean shouldSkip(String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }
        
        for (String pattern : SKIP_PATTERNS) {
            if (url.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
                return relativeUrl;
            }
            
            URI baseUri = new URI(baseUrl);
            URI resolvedUri = baseUri.resolve(relativeUrl);
            return resolvedUri.toString();
            
        } catch (Exception e) {
            log.warn("解析 URL 失败: base={}, relative={}", baseUrl, relativeUrl);
            return null;
        }
    }
    
    @Override
    public String getName() {
        return "JsRedirect";
    }
    
    @Override
    public int getPriority() {
        return 20; // 最低优先级，作为兜底
    }
}
