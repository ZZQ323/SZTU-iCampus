package cn.edu.sztui.base.infrastructure.convertor;

import cn.edu.sztui.common.cache.dto.CookieDTO;
import cn.edu.sztui.common.util.smarthttp.SmartCookie;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.microsoft.playwright.options.Cookie;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Cookie 转换器
 * 
 * 支持以下格式之间的转换：
 * - Playwright Cookie (com.microsoft.playwright.options.Cookie)
 * - Apache HttpClient Cookie (org.apache.hc.client5.http.cookie.Cookie)
 * - SmartCookie (cn.edu.sztui.common.util.smarthttp.SmartCookie)
 * - CookieDTO (JSON 序列化格式)
 * - JSON String
 */
public class CookieConverter {

    private CookieConverter() {}

    // ==================== Playwright Cookie <-> JSON ====================

    /**
     * Playwright Cookie 列表 -> JSON 字符串
     */
    public static String toCookieStrings(List<Cookie> cookies) {
        List<CookieDTO> dtos = cookies.stream()
                .map(c -> {
                    CookieDTO dto = new CookieDTO();
                    dto.setName(c.name);
                    dto.setValue(c.value);
                    dto.setUrl(c.url);
                    dto.setDomain(c.domain);
                    dto.setPath(c.path);
                    dto.setExpiryTime(c.expires != null ? c.expires.longValue() : 0l);
                    dto.setSecure(c.secure != null && c.secure);
                    dto.setHttpOnly(c.httpOnly != null && c.httpOnly);
                    dto.setSameSiteAttribute(c.sameSite);
                    return dto;
                })
                .toList();
        return JSON.toJSONString(dtos);
    }

    /**
     * JSON 字符串 -> Playwright Cookie 列表
     */
    public static List<Cookie> fromCookieDTOs(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        List<CookieDTO> dtos = JSONArray.parseArray(json, CookieDTO.class);
        return dtos.stream()
                .map(dto -> {
                    Cookie cookie = new Cookie(dto.getName(), dto.getValue());
                    cookie.setUrl(dto.getUrl());
                    cookie.setDomain(dto.getDomain());
                    cookie.setPath(dto.getPath());
                    cookie.setExpires((double) dto.getExpiryTime());
                    cookie.setSecure(dto.isSecure());
                    cookie.setHttpOnly(dto.isHttpOnly());
                    cookie.setSameSite(dto.getSameSiteAttribute());
                    return cookie;
                })
                .toList();
    }

    // ==================== SmartCookie <-> JSON ====================

    /**
     * SmartCookie 列表 -> JSON 字符串
     */
    public static String smartCookiesToJson(List<SmartCookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "[]";
        }
        List<CookieDTO> dtos = cookies.stream()
                .map(c -> {
                    CookieDTO dto = new CookieDTO();
                    dto.setName(c.getName());
                    dto.setValue(c.getValue());
                    dto.setDomain(c.getDomain());
                    dto.setPath(c.getPath());
                    dto.setExpiryTime(c.getExpires() != null ? c.getExpires().getEpochSecond() : 0l);
                    dto.setSecure(c.isSecure());
                    dto.setHttpOnly(c.isHttpOnly());
                    dto.setSameSiteAttribute(c.getSameSite() != null 
                            ? com.microsoft.playwright.options.SameSiteAttribute.valueOf(c.getSameSite()) 
                            : null);
                    return dto;
                })
                .toList();
        return JSON.toJSONString(dtos);
    }

    /**
     * JSON 字符串 -> SmartCookie 列表
     */
    public static List<SmartCookie> jsonToSmartCookies(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        List<CookieDTO> dtos = JSONArray.parseArray(json, CookieDTO.class);
        return dtos.stream()
                .map(dto -> SmartCookie.builder()
                        .name(dto.getName())
                        .value(dto.getValue())
                        .domain(dto.getDomain())
                        .path(dto.getPath() != null ? dto.getPath() : "/")
                        .expires(dto.getExpiryTime() > 0 
                                ? Instant.ofEpochSecond(dto.getExpiryTime()) 
                                : null)
                        .secure(dto.isSecure())
                        .httpOnly(dto.isHttpOnly())
                        .sameSite(dto.getSameSiteAttribute() != null 
                                ? dto.getSameSiteAttribute().name() 
                                : null)
                        .build())
                .toList();
    }

    // ==================== SmartCookie <-> Playwright Cookie ====================

    /**
     * Playwright Cookie -> SmartCookie
     */
    public static SmartCookie playwrightToSmart(Cookie pw) {
        if (pw == null) return null;
        return SmartCookie.builder()
                .name(pw.name)
                .value(pw.value)
                .domain(pw.domain)
                .path(pw.path)
                .expires(pw.expires != null && pw.expires > 0 
                        ? Instant.ofEpochSecond(pw.expires.longValue()) 
                        : null)
                .httpOnly(pw.httpOnly != null && pw.httpOnly)
                .secure(pw.secure != null && pw.secure)
                .sameSite(pw.sameSite != null ? pw.sameSite.name() : null)
                .build();
    }

    /**
     * SmartCookie -> Playwright Cookie
     */
    public static Cookie smartToPlaywright(SmartCookie smart) {
        if (smart == null) return null;
        Cookie pw = new Cookie(smart.getName(), smart.getValue());
        pw.setDomain(smart.getDomain());
        pw.setPath(smart.getPath() != null ? smart.getPath() : "/");
        if (smart.getExpires() != null) {
            pw.setExpires((double) smart.getExpires().getEpochSecond());
        }
        pw.setHttpOnly(smart.isHttpOnly());
        pw.setSecure(smart.isSecure());
        return pw;
    }

    /**
     * Playwright Cookie 列表 -> SmartCookie 列表
     */
    public static List<SmartCookie> playwrightToSmartList(List<Cookie> pwCookies) {
        if (pwCookies == null) return List.of();
        return pwCookies.stream()
                .map(CookieConverter::playwrightToSmart)
                .toList();
    }

    /**
     * SmartCookie 列表 -> Playwright Cookie 列表
     */
    public static List<Cookie> smartToPlaywrightList(List<SmartCookie> smartCookies) {
        if (smartCookies == null) return List.of();
        return smartCookies.stream()
                .map(CookieConverter::smartToPlaywright)
                .toList();
    }

    // ==================== SmartCookie <-> Apache HttpClient Cookie ====================

    /**
     * Apache BasicClientCookie -> SmartCookie
     */
    public static SmartCookie apacheToSmart(org.apache.hc.client5.http.cookie.Cookie apache) {
        if (apache == null) return null;
        return SmartCookie.builder()
                .name(apache.getName())
                .value(apache.getValue())
                .domain(apache.getDomain())
                .path(apache.getPath())
                .expires(apache.getExpiryInstant())
                .httpOnly(apache.isHttpOnly())
                .secure(apache.isSecure())
                .build();
    }

    /**
     * SmartCookie -> Apache BasicClientCookie
     */
    public static BasicClientCookie smartToApache(SmartCookie smart) {
        if (smart == null) return null;
        BasicClientCookie apache = new BasicClientCookie(smart.getName(), smart.getValue());
        apache.setDomain(smart.getDomain());
        apache.setPath(smart.getPath() != null ? smart.getPath() : "/");
        if (smart.getExpires() != null) {
            apache.setExpiryDate(Date.from(smart.getExpires()).toInstant());
        }
        apache.setHttpOnly(smart.isHttpOnly());
        apache.setSecure(smart.isSecure());
        return apache;
    }

    /**
     * Apache CookieStore -> SmartCookie 列表
     */
    public static List<SmartCookie> apacheStoreToSmartList(CookieStore cookieStore) {
        if (cookieStore == null) return List.of();
        return cookieStore.getCookies().stream()
                .map(CookieConverter::apacheToSmart)
                .toList();
    }

    /**
     * SmartCookie 列表 -> Apache CookieStore
     */
    public static CookieStore smartListToApacheStore(List<SmartCookie> smartCookies) {
        BasicCookieStore store = new BasicCookieStore();
        if (smartCookies != null) {
            for (SmartCookie smart : smartCookies) {
                store.addCookie(smartToApache(smart));
            }
        }
        return store;
    }

    // ==================== Apache HttpClient Cookie <-> JSON ====================

    /**
     * Apache CookieStore -> JSON 字符串
     */
    public static String apacheStoreToJson(CookieStore cookieStore) {
        if (cookieStore == null) return "[]";
        return smartCookiesToJson(apacheStoreToSmartList(cookieStore));
    }

    /**
     * JSON 字符串 -> Apache CookieStore
     */
    public static CookieStore jsonToApacheStore(String json) {
        List<SmartCookie> smartCookies = jsonToSmartCookies(json);
        return smartListToApacheStore(smartCookies);
    }

    // ==================== Playwright Cookie <-> Apache HttpClient Cookie ====================

    /**
     * Playwright Cookie -> Apache BasicClientCookie
     */
    public static BasicClientCookie playwrightToApache(Cookie pw) {
        return smartToApache(playwrightToSmart(pw));
    }

    /**
     * Apache Cookie -> Playwright Cookie
     */
    public static Cookie apacheToPlaywright(org.apache.hc.client5.http.cookie.Cookie apache) {
        return smartToPlaywright(apacheToSmart(apache));
    }

    /**
     * Playwright Cookie 列表 -> Apache CookieStore
     */
    public static CookieStore playwrightToApacheStore(List<Cookie> pwCookies) {
        return smartListToApacheStore(playwrightToSmartList(pwCookies));
    }

    /**
     * Apache CookieStore -> Playwright Cookie 列表
     */
    public static List<Cookie> apacheStoreToPlaywrightList(CookieStore cookieStore) {
        return smartToPlaywrightList(apacheStoreToSmartList(cookieStore));
    }
}
