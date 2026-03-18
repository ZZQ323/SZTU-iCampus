package cn.edu.sztui.common.util.smarthttp.dto;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 智能 HTTP 请求对象
 */
@Data
@Builder
public class SmartRequest {
    
    private String url;
    
    @Builder.Default
    private String method = "GET";
    
    @Builder.Default
    private Map<String, String> formData = new HashMap<>();
    
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();
    
    /** 超时时间（秒），0 表示使用默认值 */
    @Builder.Default
    private int timeoutSeconds = 0;
    
    /** 是否跟随重定向 */
    @Builder.Default
    private boolean followRedirects = true;
    
    /** Referer 头 */
    private String referer;
    
    // ==================== 静态工厂方法 ====================
    
    public static SmartRequest get(String url) {
        return SmartRequest.builder()
                .url(url)
                .method("GET")
                .build();
    }
    
    public static SmartRequest post(String url, Map<String, String> formData) {
        return SmartRequest.builder()
                .url(url)
                .method("POST")
                .formData(formData != null ? formData : new HashMap<>())
                .build();
    }
    
    public static SmartRequest ajax(String url, Map<String, String> formData) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        
        return SmartRequest.builder()
                .url(url)
                .method("POST")
                .formData(formData != null ? formData : new HashMap<>())
                .headers(headers)
                .followRedirects(false)  // AJAX 请求不跟随重定向
                .build();
    }
}
