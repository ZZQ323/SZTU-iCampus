package cn.edu.sztui.common.util.smarthttp.redirect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Data 参数重定向处理器（学校 WebVPN Portal 特定）
 * 
 * 处理登录成功后的重定向页面：
 * URL: https://webvpn.sztu.edu.cn/portal/?data=eyJjb2RlIjoxLC4uLn0=
 * 
 * data 参数是 Base64 编码的 JSON：
 * {
 *   "code": 1,
 *   "msg": "Thdportal auth success.",
 *   "redirectUrl": "https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal"
 * }
 * 
 * 实际重定向 URL：redirectUrl + "?sangfor_redirect=1"
 */
@Slf4j
public class DataParameterRedirectHandler implements RedirectHandler {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String detectRedirect(String currentUrl, int statusCode, Map<String, String> headers, String body) {
        // 只处理 200 状态码
        if (statusCode != 200) {
            return null;
        }
        
        // 检查 URL 是否包含 data 参数
        if (!currentUrl.contains("data=")) {
            return null;
        }
        
        try {
            // 从 URL 提取 data 参数
            URI uri = new URI(currentUrl);
            String query = uri.getQuery();
            if (query == null) {
                return null;
            }
            
            String dataParam = null;
            for (String param : query.split("&")) {
                if (param.startsWith("data=")) {
                    dataParam = param.substring(5);
                    break;
                }
            }
            
            if (dataParam == null || dataParam.isEmpty()) {
                return null;
            }
            
            // URL 解码
            dataParam = URLDecoder.decode(dataParam, StandardCharsets.UTF_8);
            
            // Base64 解码
            String json = new String(Base64.getDecoder().decode(dataParam), StandardCharsets.UTF_8);
            
            // JSON 解析
            JsonNode node = objectMapper.readTree(json);
            
            // 检查 code
            int code = node.path("code").asInt(0);
            if (code != 1) {
                log.warn("[DataParameter] code != 1: {}", json);
                return null;
            }
            
            // 提取 redirectUrl
            String redirectUrl = node.path("redirectUrl").asText(null);
            if (redirectUrl == null || redirectUrl.isEmpty()) {
                return null;
            }
            
            // 构建最终 URL
            String finalUrl = redirectUrl;
            if (finalUrl.contains("?")) {
                finalUrl += "&sangfor_redirect=1";
            } else {
                finalUrl += "?sangfor_redirect=1";
            }
            
            log.debug("[DataParameter] {} -> {}", currentUrl, finalUrl);
            return finalUrl;
            
        } catch (Exception e) {
            log.debug("[DataParameter] 解析失败: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public String getName() {
        return "DataParameter";
    }
    
    @Override
    public int getPriority() {
        return 5; // 中等优先级
    }
}
