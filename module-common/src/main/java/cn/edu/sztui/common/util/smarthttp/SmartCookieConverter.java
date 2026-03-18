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
 * ⭐ 修复：expires 字段兼容 ISO 字符串和 epoch 数字两种格式
 * 原因：smartCookiesToJson() 用 fastjson2 序列化 Instant → ISO 字符串
 * jsonToSmartCookies() 读取时必须兼容两种格式
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

    public static String smartCookiesToJson(List<SmartCookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "[]";
        }
        return JSON.toJSONString(cookies);
    }

    /**
     * JSON 字符串 -> SmartCookie 列表
     * <p>
     * ⭐ 修复点：第 79 行 jo.getLong("expires") 改为 parseExpiresField(jo)
     * 兼容 ISO 字符串 / epoch 数字 / null
     */
    public static List<SmartCookie> jsonToSmartCookies(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }

        return JSONArray.parseArray(json).stream()
                .map(obj -> {
                    com.alibaba.fastjson2.JSONObject jo = (com.alibaba.fastjson2.JSONObject) obj;
                    return SmartCookie.builder()
                            .name(jo.getString("name"))
                            .value(jo.getString("value"))
                            .domain(jo.getString("domain"))
                            .path(jo.getString("path") != null ? jo.getString("path") : "/")
                            .expires(parseExpiresField(jo))   // ⭐ 修复
                            .secure(jo.getBooleanValue("secure"))
                            .httpOnly(jo.getBooleanValue("httpOnly"))
                            .sameSite(jo.getString("sameSite"))
                            .build();
                })
                .toList();
    }

    /**
     * ⭐ 新增：安全解析 expires 字段
     * <p>
     * fastjson2 序列化 Instant 时默认输出 ISO 字符串 "2026-03-18T13:40:25.157450Z"，
     * 但旧数据/外部数据可能是 epoch 秒数。此方法兼容所有格式。
     */
    private static Instant parseExpiresField(com.alibaba.fastjson2.JSONObject jo) {
        Object raw = jo.get("expires");
        if (raw == null) return null;

        // 情况1：数字（epoch 秒）
        if (raw instanceof Number num) {
            long val = num.longValue();
            return val > 0 ? Instant.ofEpochSecond(val) : null;
        }

        // 情况2：字符串
        if (raw instanceof String str) {
            if (str.isEmpty()) return null;
            // ISO 格式：含 T 和 - 的字符串
            if (str.contains("T") && str.contains("-")) {
                try {
                    return Instant.parse(str);
                } catch (Exception e) {
                    return null; // 解析失败，忽略（expires 对爬取无影响）
                }
            }
            // 纯数字字符串
            try {
                long val = Long.parseLong(str);
                return val > 0 ? Instant.ofEpochSecond(val) : null;
            } catch (NumberFormatException ignored) {
            }
        }

        return null;
    }

    // ==================== 批量转换 ====================

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