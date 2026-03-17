package cn.edu.sztui.base.infrastructure.persistence.convertor;

import cn.edu.sztui.common.util.smarthttp.SmartCookie;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.time.Instant;
import java.util.List;

/**
 * Cookie 转换器（精简版 - 移除 Playwright 依赖）
 * <p>
 * 只保留 SmartCookie <-> JSON 的转换
 */
public class CookieConverter {

    private CookieConverter() {
    }

    // ==================== SmartCookie <-> JSON ====================

    /**
     * SmartCookie 列表 -> JSON 字符串
     */
    public static String smartCookiesToJson(List<SmartCookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "[]";
        }
        return JSON.toJSONString(cookies);
    }

    /**
     * JSON 字符串 -> SmartCookie 列表
     */
    public static List<SmartCookie> jsonToSmartCookies(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }

        // 直接解析为 SmartCookie（需要处理 expires 字段）
        return JSONArray.parseArray(json).stream()
                .map(obj -> {
                    com.alibaba.fastjson2.JSONObject jo = (com.alibaba.fastjson2.JSONObject) obj;
                    return SmartCookie.builder()
                            .name(jo.getString("name"))
                            .value(jo.getString("value"))
                            .domain(jo.getString("domain"))
                            .path(jo.getString("path") != null ? jo.getString("path") : "/")
                            .expires(jo.getLong("expires") != null && jo.getLong("expires") > 0
                                    ? Instant.ofEpochSecond(jo.getLong("expires"))
                                    : null)
                            .secure(jo.getBooleanValue("secure"))
                            .httpOnly(jo.getBooleanValue("httpOnly"))
                            .sameSite(jo.getString("sameSite"))
                            .build();
                })
                .toList();
    }
}