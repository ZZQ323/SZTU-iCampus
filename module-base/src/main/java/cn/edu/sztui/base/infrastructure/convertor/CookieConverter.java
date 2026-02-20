package cn.edu.sztui.base.infrastructure.convertor;

import cn.edu.sztui.common.cache.dto.CookieDTO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.microsoft.playwright.options.Cookie;

import java.util.List;

/**
 * playwright Cookie序列化
 */
public class CookieConverter {
    // 直接序列化Playwright的Cookie对象为JSON可能不会包含所有字段
    // 因为默认的序列化可能只序列化public字段或getter/setter方法。
    // 因此，通常我们需要将Cookie对象转换为一个Map或自定义的DTO来进行序列化。
    public static String toCookieStrings(List<Cookie> cookies) {
        List<CookieDTO> dtos = cookies.stream()
                .map(c -> {
                    // playwright 的 cookie 序列化
                    CookieDTO dto = new CookieDTO();
                    dto.setName(c.name);
                    dto.setValue(c.value);
                    dto.setUrl(c.url);
                    dto.setDomain(c.domain);
                    dto.setPath(c.path);
                    dto.setExpiryTime(c.expires.longValue());
                    dto.setSecure(c.secure);
                    dto.setHttpOnly(c.httpOnly);
                    dto.setSameSiteAttribute(c.sameSite);
                    return dto;
                })
                .toList();
        return JSON.toJSONString(dtos);
    }

    public static List<Cookie> fromCookieDTOs(String json) {
        List<CookieDTO> dtos = JSONArray.parseArray(json, CookieDTO.class);
        return dtos.stream()
                .map(dto -> {
                    // playwright 的 cookie 反序列化
                    Cookie cckk =new Cookie(dto.getName(), dto.getValue());
                    cckk.setUrl(dto.getUrl());
                    cckk.setDomain(dto.getDomain());
                    cckk.setPath(dto.getPath());
                    cckk.setExpires(dto.getExpiryTime());
                    cckk.setSecure(dto.isSecure());
                    cckk.setHttpOnly(dto.isHttpOnly());
                    cckk.setSameSite(dto.getSameSiteAttribute());
                    return cckk;
                })
                .toList();
    }
}
