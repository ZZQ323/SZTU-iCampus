package cn.edu.sztui.common.util.smarthttp.dto;

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

}
