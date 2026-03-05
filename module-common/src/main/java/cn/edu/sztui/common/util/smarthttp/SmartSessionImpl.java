package cn.edu.sztui.common.util.smarthttp;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能 HTTP 会话实现
 * 
 * 线程安全的 Cookie 管理
 */
@Slf4j
public class SmartSessionImpl implements SmartSession {
    
    /** 
     * Cookie 存储：domain -> name -> cookie 
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final Map<String, Map<String, SmartCookie>> cookieStore = new ConcurrentHashMap<>();
    
    public SmartSessionImpl() {
    }
    
    public SmartSessionImpl(List<SmartCookie> cookies) {
        if (cookies != null) {
            addCookies(cookies);
        }
    }
    
    @Override
    public List<SmartCookie> getCookies() {
        List<SmartCookie> result = new ArrayList<>();
        for (Map<String, SmartCookie> domainCookies : cookieStore.values()) {
            result.addAll(domainCookies.values());
        }
        return result;
    }
    
    @Override
    public void addCookie(SmartCookie cookie) {
        if (cookie == null || cookie.getName() == null || cookie.getDomain() == null) {
            return;
        }
        
        String domain = normalizeDomain(cookie.getDomain());
        cookieStore.computeIfAbsent(domain, k -> new ConcurrentHashMap<>())
                   .put(cookie.getName(), cookie);
        
        log.trace("添加 Cookie: {}={} (domain={})", cookie.getName(), 
                  cookie.getValue().length() > 20 ? cookie.getValue().substring(0, 20) + "..." : cookie.getValue(),
                  domain);
    }
    
    @Override
    public void addCookies(List<SmartCookie> cookies) {
        if (cookies == null) return;
        for (SmartCookie cookie : cookies) {
            addCookie(cookie);
        }
    }
    
    @Override
    public void clearCookies() {
        cookieStore.clear();
        log.debug("已清除所有 Cookies");
    }
    
    @Override
    public List<SmartCookie> getCookiesForDomain(String domain) {
        List<SmartCookie> result = new ArrayList<>();
        String normalizedDomain = normalizeDomain(domain);
        
        for (Map.Entry<String, Map<String, SmartCookie>> entry : cookieStore.entrySet()) {
            String cookieDomain = entry.getKey();
            
            // 检查域名匹配
            if (normalizedDomain.equals(cookieDomain) || normalizedDomain.endsWith("." + cookieDomain)) {
                for (SmartCookie cookie : entry.getValue().values()) {
                    if (!cookie.isExpired()) {
                        result.add(cookie);
                    }
                }
            }
        }
        
        return result;
    }
    
    @Override
    public String buildCookieHeader(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            
            List<SmartCookie> matchingCookies = new ArrayList<>();
            
            for (Map.Entry<String, Map<String, SmartCookie>> entry : cookieStore.entrySet()) {
                String cookieDomain = entry.getKey();
                
                // 检查域名匹配
                if (host.equals(cookieDomain) || host.endsWith("." + cookieDomain)) {
                    for (SmartCookie cookie : entry.getValue().values()) {
                        // 检查过期和路径
                        if (!cookie.isExpired() && cookie.matchesPath(path)) {
                            matchingCookies.add(cookie);
                        }
                    }
                }
            }
            
            if (matchingCookies.isEmpty()) {
                return null;
            }
            
            return matchingCookies.stream()
                    .map(SmartCookie::toHeaderValue)
                    .collect(Collectors.joining("; "));
                    
        } catch (Exception e) {
            log.warn("构建 Cookie 头失败: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public void parseAndAddCookies(List<String> setCookieHeaders, String url) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }
        
        String defaultDomain = null;
        try {
            URI uri = new URI(url);
            defaultDomain = uri.getHost();
        } catch (Exception e) {
            log.warn("解析 URL 失败: {}", url);
        }
        
        for (String header : setCookieHeaders) {
            try {
                SmartCookie cookie = parseSetCookieHeader(header, defaultDomain);
                if (cookie != null) {
                    addCookie(cookie);
                }
            } catch (Exception e) {
                log.warn("解析 Set-Cookie 头失败: {}", header);
            }
        }
    }
    
    /**
     * 解析 Set-Cookie 头
     */
    private SmartCookie parseSetCookieHeader(String header, String defaultDomain) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        
        String[] parts = header.split(";");
        if (parts.length == 0) {
            return null;
        }
        
        // 解析 name=value
        String[] nameValue = parts[0].split("=", 2);
        if (nameValue.length < 2) {
            return null;
        }
        
        String name = nameValue[0].trim();
        String value = nameValue[1].trim();
        
        // 默认值
        String domain = defaultDomain;
        String path = "/";
        Instant expires = null;
        boolean httpOnly = false;
        boolean secure = false;
        String sameSite = null;
        
        // 解析其他属性
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            String lowerPart = part.toLowerCase();
            
            if (lowerPart.startsWith("domain=")) {
                domain = part.substring(7).trim();
                if (domain.startsWith(".")) {
                    domain = domain.substring(1);
                }
            } else if (lowerPart.startsWith("path=")) {
                path = part.substring(5).trim();
            } else if (lowerPart.startsWith("expires=")) {
                // 简化处理，忽略 expires
            } else if (lowerPart.startsWith("max-age=")) {
                try {
                    long maxAge = Long.parseLong(part.substring(8).trim());
                    expires = Instant.now().plusSeconds(maxAge);
                } catch (NumberFormatException ignored) {
                }
            } else if (lowerPart.equals("httponly")) {
                httpOnly = true;
            } else if (lowerPart.equals("secure")) {
                secure = true;
            } else if (lowerPart.startsWith("samesite=")) {
                sameSite = part.substring(9).trim();
            }
        }
        
        return SmartCookie.builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expires(expires)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite(sameSite)
                .build();
    }
    
    private String normalizeDomain(String domain) {
        if (domain == null) {
            return "";
        }
        // 移除前导点
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        return domain.toLowerCase();
    }
    
    @Override
    public void close() {
        clearCookies();
    }
}
