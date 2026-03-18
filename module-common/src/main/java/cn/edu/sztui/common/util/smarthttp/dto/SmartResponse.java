package cn.edu.sztui.common.util.smarthttp.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能 HTTP 响应对象
 */
@Data
@Builder
public class SmartResponse {
    
    /** HTTP 状态码 */
    private int statusCode;
    
    /** 响应体 */
    private String body;
    
    /** 最终 URL（重定向后） */
    private String finalUrl;
    
    /** 重定向链（所有访问过的 URL） */
    @Builder.Default
    private List<String> redirectChain = new ArrayList<>();
    
    /** 响应头 */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();
    
    /** 重定向次数 */
    @Builder.Default
    private int redirectCount = 0;
    
    // ==================== 便捷方法 ====================
    
    /**
     * 是否成功（2xx 状态码）
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
    
    /**
     * 是否是重定向（3xx 状态码）
     */
    public boolean isRedirect() {
        return statusCode >= 300 && statusCode < 400;
    }
    
    /**
     * 获取 Location 头
     */
    public String getLocationHeader() {
        return headers.get("Location");
    }
    
    /**
     * 是否包含指定文本
     */
    public boolean bodyContains(String text) {
        return body != null && body.contains(text);
    }
    
    /**
     * URL 是否匹配指定模式
     */
    public boolean finalUrlMatches(String pattern) {
        return finalUrl != null && finalUrl.contains(pattern);
    }
}
