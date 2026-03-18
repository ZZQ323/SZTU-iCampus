package cn.edu.sztui.common.util.smarthttp;

import cn.edu.sztui.common.cache.dto.CookieDTO;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SmartCookie 转换工具类
 * <p>
 * 在 SmartCookie、CookieDTO、Playwright Cookie 之间转换
 */
public class SmartCookieConverter {

    private SmartCookieConverter() {
    }

    // ==================== SmartCookie <-> CookieDTO ====================

    public static SmartCookie fromDTO(CookieDTO dto) {
        if (dto == null) return null;

        return SmartCookie.builder()
                .name(dto.getName())
                .value(dto.getValue())
                .domain(dto.getDomain())
                .path(dto.getPath() != null ? dto.getPath() : "/")
                .httpOnly(dto.isHttpOnly())
                .secure(dto.isSecure())
                .build();
    }

    public static CookieDTO toDTO(SmartCookie cookie) {
        if (cookie == null) return null;

        CookieDTO dto = new CookieDTO();
        dto.setName(cookie.getName());
        dto.setValue(cookie.getValue());
        dto.setDomain(cookie.getDomain());
        dto.setPath(cookie.getPath());
        dto.setHttpOnly(cookie.isHttpOnly());
        dto.setSecure(cookie.isSecure());
        return dto;
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

    public static List<SmartCookie> fromDTOs(List<CookieDTO> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
                .map(SmartCookieConverter::fromDTO)
                .collect(Collectors.toList());
    }

    public static List<CookieDTO> toDTOs(List<SmartCookie> cookies) {
        if (cookies == null) return List.of();
        return cookies.stream()
                .map(SmartCookieConverter::toDTO)
                .collect(Collectors.toList());
    }
}
