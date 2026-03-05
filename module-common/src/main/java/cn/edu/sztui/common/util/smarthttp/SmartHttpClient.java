package cn.edu.sztui.common.util.smarthttp;

import java.util.List;
import java.util.Map;

/**
 * 智能 HTTP 客户端接口
 * 
 * 自动处理各种重定向（Location Header、Meta Refresh、JS Redirect），无需浏览器
 * 
 * 文件位置：module-common/src/main/java/cn/edu/sztui/common/util/smarthttp/SmartHttpClient.java
 */
public interface SmartHttpClient {
    
    /**
     * 执行 GET 请求，自动跟随所有重定向
     */
    SmartResponse get(String url, SmartSession session) throws SmartHttpException;
    
    /**
     * 执行 POST 请求（表单），自动跟随所有重定向
     */
    SmartResponse post(String url, Map<String, String> formData, SmartSession session) throws SmartHttpException;
    
    /**
     * 执行 POST 请求（AJAX/JSON），不跟随重定向
     */
    SmartResponse postAjax(String url, Map<String, String> formData, SmartSession session, Map<String, String> extraHeaders) throws SmartHttpException;
    
    /**
     * 执行请求，自动跟随所有重定向
     */
    SmartResponse execute(SmartRequest request, SmartSession session) throws SmartHttpException;
    
    /**
     * 执行请求，不跟随重定向（用于 AJAX 请求）
     */
    SmartResponse executeNoRedirect(SmartRequest request, SmartSession session) throws SmartHttpException;
    
    /**
     * 创建新的会话（独立的 Cookie 存储）
     */
    SmartSession newSession();
    
    /**
     * 从现有 Cookies 创建会话
     */
    SmartSession newSession(List<SmartCookie> cookies);
    
    /**
     * 获取默认超时时间（秒）
     */
    int getDefaultTimeoutSeconds();
    
    /**
     * 获取慢请求超时时间（秒）
     */
    int getSlowTimeoutSeconds();
}
