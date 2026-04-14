package cn.edu.sztui.common.util.smarthttp.service;

import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
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
        
        for (Map.Entry<String, Map<String, SmartCookie>> entry : cookieStore.entrySet()) {
            String cookieDomain = entry.getKey();
            
            // 使用统一的域名匹配逻辑
            if (domainMatches(domain, cookieDomain)) {
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
            
            log.debug("构建 Cookie 头: url={}, host={}", url, host);
            log.debug("Cookie 存储中的域名: {}", cookieStore.keySet());
            
            for (Map.Entry<String, Map<String, SmartCookie>> entry : cookieStore.entrySet()) {
                String cookieDomain = entry.getKey();
                
                // 检查域名匹配（更宽松的匹配规则）
                if (domainMatches(host, cookieDomain)) {
                    for (SmartCookie cookie : entry.getValue().values()) {
                        // 检查过期和路径
                        if (!cookie.isExpired() && cookie.matchesPath(path)) {
                            matchingCookies.add(cookie);
                            log.trace("匹配 Cookie: {}={} (domain={})", 
                                    cookie.getName(), 
                                    cookie.getValue().length() > 10 ? cookie.getValue().substring(0, 10) + "..." : cookie.getValue(),
                                    cookieDomain);
                        }
                    }
                }
            }
            
            if (matchingCookies.isEmpty()) {
                // log.warn("没有匹配的 Cookie! host={}, 存储的域名={}", host, cookieStore.keySet());
                return null;
            }
            
            String cookieHeader = matchingCookies.stream()
                    .map(SmartCookie::toHeaderValue)
                    .collect(Collectors.joining("; "));
            
            log.debug("发送 {} 个 Cookie: {}", matchingCookies.size(), 
                    cookieHeader.length() > 100 ? cookieHeader.substring(0, 100) + "..." : cookieHeader);
            
            return cookieHeader;
                    
        } catch (Exception e) {
            log.warn("构建 Cookie 头失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 检查域名是否匹配
     * 
     * 匹配规则：
     * 1. 精确匹配
     * 2. host 是 cookieDomain 的子域名
     * 3. cookieDomain 是 host 的父域名
     */
    private boolean domainMatches(String host, String cookieDomain) {
        if (host == null || cookieDomain == null) {
            return false;
        }
        
        host = host.toLowerCase();
        cookieDomain = cookieDomain.toLowerCase();
        
        // 移除前导点
        if (cookieDomain.startsWith(".")) {
            cookieDomain = cookieDomain.substring(1);
        }
        if (host.startsWith(".")) {
            host = host.substring(1);
        }
        
        // 精确匹配
        if (host.equals(cookieDomain)) {
            return true;
        }
        
        // host 是 cookieDomain 的子域名
        // 例如: host="auth-sztu-edu-cn-s.webvpn.sztu.edu.cn", cookieDomain="webvpn.sztu.edu.cn"
        if (host.endsWith("." + cookieDomain)) {
            return true;
        }
        
        // cookieDomain 是 host 的子域名（反向匹配，某些场景需要）
        // 例如: host="webvpn.sztu.edu.cn", cookieDomain="auth-sztu-edu-cn-s.webvpn.sztu.edu.cn"
        // 这种情况下，Cookie 也应该被发送（因为它们属于同一个根域）
        if (cookieDomain.endsWith("." + host)) {
            return true;
        }
        
        // 共享父域名匹配
        // 例如: host="auth-sztu-edu-cn-s.webvpn.sztu.edu.cn"
        //       cookieDomain="home-sztu-edu-cn-s.webvpn.sztu.edu.cn"
        // 它们共享 "webvpn.sztu.edu.cn"
        String hostRoot = extractRootDomain(host);
        String cookieRoot = extractRootDomain(cookieDomain);
        if (hostRoot != null && hostRoot.equals(cookieRoot)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 提取根域名（最后两段或三段）
     */
    private String extractRootDomain(String domain) {
        if (domain == null) return null;
        
        String[] parts = domain.split("\\.");
        if (parts.length < 2) return domain;
        
        // 对于 .edu.cn 这样的特殊后缀，取最后三段
        if (parts.length >= 3 && "edu".equals(parts[parts.length - 2])) {
            return parts[parts.length - 3] + "." + parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        
        // 默认取最后两段
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
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
