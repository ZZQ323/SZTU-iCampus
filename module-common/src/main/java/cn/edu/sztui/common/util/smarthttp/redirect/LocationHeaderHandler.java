package cn.edu.sztui.common.util.smarthttp.redirect;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

/**
 * Location Header 重定向处理器
 * 
 * 处理 301、302、303、307、308 状态码的 Location 头重定向
 */
@Slf4j
public class LocationHeaderHandler implements RedirectHandler {
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 3xx 状态码
        if (statusCode < 300 || statusCode >= 400) {
            return null;
        }
        
        String location = headers.get("Location");
        if (location == null) {
            location = headers.get("location"); // 大小写不敏感
        }
        
        if (location == null || location.isEmpty()) {
            return null;
        }
        
        // 解析相对 URL
        String resolvedUrl = resolveUrl(currentUrl, location);
        log.debug("[LocationHeader] {} -> {}", currentUrl, resolvedUrl);
        
        return resolvedUrl;
    }
    
    @Override
    public String getName() {
        return "LocationHeader";
    }
    
    @Override
    public int getPriority() {
        return 1; // 最高优先级
    }
    
    /**
     * 解析相对 URL
     */
    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            // 如果已经是绝对 URL，直接返回
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
