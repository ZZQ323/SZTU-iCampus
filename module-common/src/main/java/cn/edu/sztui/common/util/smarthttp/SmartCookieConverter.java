package cn.edu.sztui.common.util.smarthttp;

import cn.edu.sztui.common.cache.dto.CookieDTO;
import com.microsoft.playwright.options.Cookie;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SmartCookie 转换工具类
 * 
 * 在 SmartCookie、CookieDTO、Playwright Cookie 之间转换
 */
public class SmartCookieConverter {
    
    private SmartCookieConverter() {}
    
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
    
    // ==================== SmartCookie <-> Playwright Cookie ====================
    
    public static SmartCookie fromPlaywright(Cookie pw) {
        if (pw == null) return null;
        return SmartCookie.fromPlaywright(pw);
    }
    
    public static Cookie toPlaywright(SmartCookie cookie) {
        if (cookie == null) return null;
        return cookie.toPlaywright();
    }
    
    public static List<SmartCookie> fromPlaywrightList(List<Cookie> pwCookies) {
        if (pwCookies == null) return List.of();
        return pwCookies.stream()
                .map(SmartCookieConverter::fromPlaywright)
                .collect(Collectors.toList());
    }
    
    public static List<Cookie> toPlaywrightList(List<SmartCookie> cookies) {
        if (cookies == null) return List.of();
        return cookies.stream()
                .map(SmartCookieConverter::toPlaywright)
                .collect(Collectors.toList());
    }
}
