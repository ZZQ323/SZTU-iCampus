package cn.edu.sztui.common.util.smarthttp.redirect;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meta Refresh 重定向处理器
 * 
 * 处理 <meta http-equiv="refresh" content="0;url=..."> 形式的重定向
 */
@Slf4j
public class MetaRefreshHandler implements RedirectHandler {
    
    /**
     * 匹配 meta refresh 标签
     * 示例：
     * <meta http-equiv="refresh" content="0;url=https://xxx">
     * <meta http-equiv="refresh" content="0; URL=https://xxx">
     * <meta http-equiv='refresh' content='0;url=https://xxx'>
     */
    private static final Pattern META_REFRESH_PATTERN = Pattern.compile(
            "<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]+content\\s*=\\s*[\"']?\\d+\\s*;\\s*url\\s*=\\s*([^\"'\\s>]+)",
            Pattern.CASE_INSENSITIVE
    );
    
    // 备用模式：content 在前
    private static final Pattern META_REFRESH_PATTERN_ALT = Pattern.compile(
            "<meta[^>]+content\\s*=\\s*[\"']?\\d+\\s*;\\s*url\\s*=\\s*([^\"'\\s>]+)[^>]+http-equiv\\s*=\\s*[\"']?refresh",
            Pattern.CASE_INSENSITIVE
    );
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 200 状态码
        if (statusCode != 200 || body == null || body.isEmpty()) {
            return null;
        }
        
        // 只检查 HTML 前 4KB（meta 标签通常在 head 中）
        String headPart = body.length() > 4096 ? body.substring(0, 4096) : body;
        
        // 尝试主模式
        Matcher matcher = META_REFRESH_PATTERN.matcher(headPart);
        if (matcher.find()) {
            String url = matcher.group(1).trim();
            String resolvedUrl = resolveUrl(currentUrl, url);
            log.debug("[MetaRefresh] {} -> {}", currentUrl, resolvedUrl);
            return resolvedUrl;
        }
        
        // 尝试备用模式
        matcher = META_REFRESH_PATTERN_ALT.matcher(headPart);
        if (matcher.find()) {
            String url = matcher.group(1).trim();
            String resolvedUrl = resolveUrl(currentUrl, url);
            log.debug("[MetaRefresh/Alt] {} -> {}", currentUrl, resolvedUrl);
            return resolvedUrl;
        }
        
        return null;
    }
    
    @Override
    public String getName() {
        return "MetaRefresh";
    }
    
    @Override
    public int getPriority() {
        return 2;
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
            return relativeUrl;
        }
    }
}
