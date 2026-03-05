package cn.edu.sztui.common.util.smarthttp.redirect;

import java.util.Map;

/**
 * 重定向处理器接口
 * 
 * 使用责任链模式处理各种重定向
 */
public interface RedirectHandler {
    
    /**
     * 检测响应中是否包含重定向
     * 
     * @param currentUrl 当前 URL
     * @param statusCode HTTP 状态码
     * @param headers 响应头
     * @param body 响应体（可能为 null）
     * @return 重定向目标 URL，如果没有重定向返回 null
     */
    String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body);
    
    /**
     * 处理器名称（用于日志）
     */
    String getName();
    
    /**
     * 处理器优先级（数字越小优先级越高）
     */
    int getPriority();
}
