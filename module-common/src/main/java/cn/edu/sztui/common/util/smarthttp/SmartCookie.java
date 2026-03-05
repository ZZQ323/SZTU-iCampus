package cn.edu.sztui.common.util.smarthttp;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Cookie 对象
 */
@Data
@Builder
public class SmartCookie {
    
    private String name;
    private String value;
    private String domain;
    private String path;
    private Instant expires;
    private boolean httpOnly;
    private boolean secure;
    private String sameSite;
    
    /**
     * 是否过期
     */
    public boolean isExpired() {
        if (expires == null) {
            return false; // 会话 Cookie，不过期
        }
        return Instant.now().isAfter(expires);
    }
    
    /**
     * 是否匹配指定域名
     */
    public boolean matchesDomain(String targetDomain) {
        if (domain == null || targetDomain == null) {
            return false;
        }
        
        String cookieDomain = domain.startsWith(".") ? domain.substring(1) : domain;
        
        return targetDomain.equals(cookieDomain) 
               || targetDomain.endsWith("." + cookieDomain);
    }
    
    /**
     * 是否匹配指定路径
     */
    public boolean matchesPath(String targetPath) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        if (targetPath == null || targetPath.isEmpty()) {
            targetPath = "/";
        }
        return targetPath.startsWith(path);
    }
    
    /**
     * 转换为请求头格式
     */
    public String toHeaderValue() {
        return name + "=" + value;
    }
    
    /**
     * 从 Playwright Cookie 格式转换
     */
    public static SmartCookie fromPlaywright(com.microsoft.playwright.options.Cookie pw) {
        return SmartCookie.builder()
                .name(pw.name)
                .value(pw.value)
                .domain(pw.domain)
                .path(pw.path)
                .expires(pw.expires != null && pw.expires > 0 
                        ? Instant.ofEpochSecond(pw.expires.longValue()) 
                        : null)
                .httpOnly(pw.httpOnly != null && pw.httpOnly)
                .secure(pw.secure != null && pw.secure)
                .sameSite(pw.sameSite != null ? pw.sameSite.name() : null)
                .build();
    }
    
    /**
     * 转换为 Playwright Cookie 格式
     */
    public com.microsoft.playwright.options.Cookie toPlaywright() {
        com.microsoft.playwright.options.Cookie pw = new com.microsoft.playwright.options.Cookie(name, value);
        pw.setDomain(domain);
        pw.setPath(path != null ? path : "/");
        if (expires != null) {
            pw.setExpires((double) expires.getEpochSecond());
        }
        pw.setHttpOnly(httpOnly);
        pw.setSecure(secure);
        return pw;
    }
}
