package cn.edu.sztui.common.cache.dto;

import com.microsoft.playwright.options.SameSiteAttribute;
import lombok.Data;

@Data
public class CookieDTO {
    private String name;
    private String value;
    private String url;
    // 控制哪个域名能访问该 Cookie
    private String domain;
    // 控制域名下的哪个路径能访问，比如 /（域名下所有路径）、/user（仅用户中心路径）
    private String path;
    // 以毫秒时间戳表示 Cookie 的失效时间；若为 null/0，代表 “会话级 Cookie”（浏览器关闭即失效）
    private Long expiryTime;
    private boolean httpOnly;
    // 	true 时，该 Cookie 仅在 HTTPS 加密连接下传输（HTTP 连接不发送），防止数据被窃听；false 时 HTTP/HTTPS 都可传输
    private boolean secure;
    private SameSiteAttribute sameSiteAttribute;
}
