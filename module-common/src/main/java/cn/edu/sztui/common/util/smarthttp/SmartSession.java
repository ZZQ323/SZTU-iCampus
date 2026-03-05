package cn.edu.sztui.common.util.smarthttp;

import java.util.List;

/**
 * 智能 HTTP 会话接口
 * 
 * 每个会话有独立的 Cookie 存储，支持并发使用
 */
public interface SmartSession extends AutoCloseable {
    
    /**
     * 获取当前所有 Cookies
     */
    List<SmartCookie> getCookies();
    
    /**
     * 添加 Cookie
     */
    void addCookie(SmartCookie cookie);
    
    /**
     * 添加多个 Cookies
     */
    void addCookies(List<SmartCookie> cookies);
    
    /**
     * 清除所有 Cookies
     */
    void clearCookies();
    
    /**
     * 获取指定域名的 Cookies
     */
    List<SmartCookie> getCookiesForDomain(String domain);
    
    /**
     * 构建 Cookie 请求头字符串
     */
    String buildCookieHeader(String url);
    
    /**
     * 从 Set-Cookie 头解析并添加 Cookies
     */
    void parseAndAddCookies(List<String> setCookieHeaders, String url);
    
    /**
     * 关闭会话
     */
    @Override
    void close();
}
