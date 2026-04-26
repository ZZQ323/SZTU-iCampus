package cn.edu.sztui.base.web;

import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;

import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源代理控制器
 * <p>
 * 解决的问题：
 * 1. 公文通图片/附件需要学校 Cookie 才能访问，小程序无法直接携带
 * 2. 小程序的 downloadFile 不能自动带 Cookie
 * 3. WebVPN 资源可能检查 Host/Referer 头
 * <p>
 * 流程：
 * 小程序 → /proxy/image?url=xxx （带 X-School-Cookies header）
 *       → 后端从请求 header 获取学校 Cookie
 *       → 后端带 Cookie + Host 头请求学校服务器
 *       → 返回二进制流给小程序
 * <p>
 * 放置位置：module-base/src/main/java/cn/edu/sztui/base/web/ProxyController.java
 */
@Slf4j
@RestController
@RequestMapping("/proxy")
@Tag(name = "资源代理", description = "代理访问需要学校 Cookie 的图片和附件")
public class ProxyController {

    /**
     * 复用的 trust-all HttpClient。
     * <p>
     * 学校 WebVPN（jwxt/nbw 等 *-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118）使用学校内签证书，
     * 不在 JVM 默认 CA truststore 里，用 {@code HttpClients.createDefault()} 会直接抛
     * {@code PKIX path building failed}。这里沿用 SmartHttpClientImpl 的 TrustAll + NoopHostname
     * 策略——反正 {@link #isAllowedDomain} 已经白名单到 *.sztu.edu.cn，安全暴露面可控。
     */
    private CloseableHttpClient httpClient;

    @PostConstruct
    public void init() throws Exception {
        var sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();
        var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();
        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(10)
                .setSSLSocketFactory(sslSocketFactory)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .build();
    }

    /** 文件扩展名 → Content-Type 映射 */
    private static final Map<String, String> MIME_MAP = new ConcurrentHashMap<>();
    static {
        // 图片
        MIME_MAP.put("jpg", "image/jpeg");
        MIME_MAP.put("jpeg", "image/jpeg");
        MIME_MAP.put("png", "image/png");
        MIME_MAP.put("gif", "image/gif");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("webp", "image/webp");
        MIME_MAP.put("bmp", "image/bmp");
        MIME_MAP.put("ico", "image/x-icon");
        // 文档
        MIME_MAP.put("pdf", "application/pdf");
        MIME_MAP.put("doc", "application/msword");
        MIME_MAP.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME_MAP.put("xls", "application/vnd.ms-excel");
        MIME_MAP.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME_MAP.put("ppt", "application/vnd.ms-powerpoint");
        MIME_MAP.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        // 压缩包
        MIME_MAP.put("zip", "application/zip");
        MIME_MAP.put("rar", "application/x-rar-compressed");
        MIME_MAP.put("7z", "application/x-7z-compressed");
    }

    /**
     * 代理图片
     * <p>
     * 用于 rich-text 中的内联图片。后端爬取详情时，
     * 将 img src 替换为 /proxy/image?url=xxx，前端渲染时自动走代理。
     *
     * @param url 原始图片 URL（需 URL 编码）
     */
    @GetMapping("/image")
    @Operation(summary = "代理图片", description = "代理访问需要 Cookie 的图片资源")
    public void proxyImage(
            @Parameter(description = "图片 URL（URL 编码）", required = true)
            @RequestParam String url,
            HttpServletResponse response) {

        String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);

        log.debug("代理图片: url={}", decodedUrl);

        // 安全检查：只允许代理学校域名的资源
        if (!isAllowedDomain(decodedUrl)) {
            log.warn("拒绝代理非学校域名: {}", decodedUrl);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        try {
            byte[] data = fetchResource(decodedUrl);
            if (data == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // 设置响应头
            String contentType = guessContentType(decodedUrl);
            response.setContentType(contentType);
            response.setContentLength(data.length);
            // 允许小程序缓存图片 1 小时
            response.setHeader("Cache-Control", "public, max-age=3600");

            OutputStream out = response.getOutputStream();
            out.write(data);
            out.flush();

        } catch (Exception e) {
            log.error("代理图片失败: url={}, error={}", decodedUrl, e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 代理附件下载
     * <p>
     * 前端调用 uni.downloadFile({ url: '/proxy/attachment?url=xxx&filename=yyy' })
     * 然后用 uni.openDocument 打开（showMenu=true 可转发/保存/用其他应用打开）
     *
     * @param url      原始附件 URL（需 URL 编码）
     * @param filename 文件名（用于 Content-Disposition）
     */
    @GetMapping("/attachment")
    @Operation(summary = "代理附件下载", description = "代理下载需要 Cookie 的附件")
    public void proxyAttachment(
            @Parameter(description = "附件 URL（URL 编码）", required = true)
            @RequestParam String url,
            @Parameter(description = "文件名")
            @RequestParam(required = false) String filename,
            HttpServletResponse response) {

        String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);

        log.info("代理附件下载: url={}, filename={}", decodedUrl, filename);

        if (!isAllowedDomain(decodedUrl)) {
            log.warn("拒绝代理非学校域名: {}", decodedUrl);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        try {
            byte[] data = fetchResource(decodedUrl);
            if (data == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // 推断文件名
            if (!StringUtils.hasText(filename)) {
                filename = extractFilenameFromUrl(decodedUrl);
            }

            // 设置响应头
            String contentType = guessContentType(decodedUrl);
            response.setContentType(contentType);
            response.setContentLength(data.length);

            // Content-Disposition：告诉浏览器/小程序这是附件
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename);

            // 不缓存附件（可能更新）
            response.setHeader("Cache-Control", "no-cache");

            OutputStream out = response.getOutputStream();
            out.write(data);
            out.flush();

            log.info("附件下载成功: filename={}, size={}KB", filename, data.length / 1024);

        } catch (Exception e) {
            log.error("代理附件失败: url={}, error={}", decodedUrl, e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    // ==================== 核心：带 Cookie 请求资源 ====================

    /**
     * 用用户的学校 Cookie 请求资源，返回二进制数据
     * <p>
     * ⭐ 关键：设置 Host 头为目标域名，避免 WebVPN 拒绝
     * <p>
     * Cookies 从请求 header（UserContext）获取，不从 Redis 读取。
     */
    private byte[] fetchResource(String url) {
        // 1. 从 UserContext 获取前端通过 header 传来的 Cookie（可能为空）
        //    小程序 <image> 标签和 rich-text 不会附加自定义 header，所以 Cookie 可能缺失。
        //    学校公开资源（如 /__local/ 下的校历图）不需要 Cookie，仅在有 Cookie 时附带。
        TokenMessage ctx = UserContext.getContext();
        String cookiesJson = (ctx != null) ? ctx.getSchoolCookiesJson() : null;

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String requestPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            StringBuilder cookieHeader = new StringBuilder();
            int totalCookies = 0;
            int matchedCookies = 0;

            if (StringUtils.hasText(cookiesJson)) {
                // 关键：过滤掉前端发来的死 cookie。前端可能因为 merge 历史积压
                // 还带着 expired / 空值 cookie，发给学校 → 414。统一在出口干净。
                List<SmartCookie> cookies = SmartCookieConverter.filterAlive(
                        SmartCookieConverter.jsonToSmartCookies(cookiesJson));
                totalCookies = cookies.size();
                StringBuilder sentNames = new StringBuilder();
                for (SmartCookie c : cookies) {
                    if (isCookieApplicable(host, c.getDomain(), c.getPath(), requestPath)) {
                        if (cookieHeader.length() > 0) cookieHeader.append("; ");
                        cookieHeader.append(c.getName()).append("=").append(c.getValue());
                        matchedCookies++;
                        if (sentNames.length() > 0) sentNames.append(",");
                        sentNames.append(c.getName());
                    }
                }
                log.info("fetchResource -> host={} path={} cookies(alive total/matched)={}/{} sent=[{}] url={}",
                        host, requestPath, totalCookies, matchedCookies, sentNames, url);
            } else {
                log.info("fetchResource -> host={} cookies(total/matched)=0/0 url={}", host, url);
            }

            // 3. 用 trust-all 复用 HttpClient 请求（SmartHttpClient 可能不返回二进制）
            HttpGet httpGet = new HttpGet(url);

            if (cookieHeader.length() > 0) {
                httpGet.setHeader("Cookie", cookieHeader.toString());
            }
            httpGet.setHeader("Host", host);
            httpGet.setHeader("Referer", extractOrigin(url) + "/");
            httpGet.setHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            return httpClient.execute(httpGet, response -> {
                int status = response.getCode();
                Header ctHeader = response.getFirstHeader("Content-Type");
                String ct = ctHeader != null ? ctHeader.getValue() : "(none)";
                Header locationHeader = response.getFirstHeader("Location");
                String location = locationHeader != null ? locationHeader.getValue() : null;

                if (status != 200) {
                    // 学校的 VWebServer / 博达 CMS 常用非标错误码（414 代表 session 问题、
                    // 200+HTML 代表未登录等）。**把完整响应体落盘**到 tmp/proxy-errors/，
                    // 这样下次复现直接打开 HTML 就能看到学校的真实错误内容，不用猜。
                    // 教训：log 只 500 字节时，调试浪费时间，因为关键信息经常在后半段。
                    byte[] errBody = null;
                    try {
                        errBody = EntityUtils.toByteArray(response.getEntity());
                    } catch (Exception ignore) { /* body 读失败就算了 */ }

                    String dumpPath = dumpErrorBody(status, url, errBody, ctHeader);
                    String preview = "(no body)";
                    if (errBody != null && errBody.length > 0) {
                        int n = Math.min(errBody.length, 500);
                        preview = new String(errBody, 0, n, StandardCharsets.UTF_8)
                                .replaceAll("\\s+", " ");
                    }
                    log.warn("fetchResource 上游非 200: status={} content-type={} location={} bodyBytes={} dump={} bodyPreview[0..500]={} url={}",
                            status, ct, location,
                            errBody != null ? errBody.length : 0, dumpPath, preview, url);
                    return null;
                }
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                // WebVPN / 博达 CMS 常见的"伪 200 HTML 登录页"：
                // 无 cookie 或 cookie 过期时，服务端返回 200 + 登录表单 HTML，而不是 401/302。
                // 嗅探 body 前 1KB，若是 HTML 且匹配登录关键字，视同失败，让前端感知登录过期。
                if (looksLikeLoginHtml(body, ctHeader)) {
                    int n = Math.min(body.length, 200);
                    String preview = new String(body, 0, n, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                    log.warn("fetchResource 命中登录页嗅探: content-type={} body[0..{}]={} url={}",
                            ct, n, preview, url);
                    return null;
                }
                log.info("fetchResource 成功: status=200 content-type={} size={}KB url={}",
                        ct, body.length / 1024, url);
                return body;
            });

        } catch (Exception e) {
            log.error("fetchResource 异常: url={}, error={}", url, e.getMessage(), e);
            return null;
        }
    }

    /**
     * cookie 是否适用于当前请求 (host, path) —— **严格 RFC 6265**。
     * <p>
     * 浏览器只把 (domain, path) 三元组匹配的 cookie 发出去，**绝不会**把整个 jar
     * 一股脑塞 Cookie 头。学校 VWebServer 对 cookie 头格式异常很敏感：
     * 把 host-only 的 `SESSION @ auth-sztu-edu-cn-s` 错发给 nbw 子域 → 414。
     * <p>
     * 规则：
     * <ul>
     *   <li>cookie.domain 以 . 开头：去掉点（HAR 里学校的 cookie 都不带点，但 RFC 6265 要兼容）</li>
     *   <li>host == domain（host-only cookie）OR host.endsWith("." + domain)（domain cookie）</li>
     *   <li>请求 path 以 cookie.path 开头（path 默认 "/" 时永远匹配）</li>
     * </ul>
     * <p>
     * 之前有"两边都 sztu.edu.cn 就放行"的兜底——是错的，**已删除**。它把
     * `SESSION @ auth-s` 错发给 nbw，把 `JSESSIONID @ home-s/bmportal` 错发给
     * `nbw/system/_content/`，这是几天来 414 的根因。
     */
    private static boolean isCookieApplicable(String host, String cookieDomain, String cookiePath, String requestPath) {
        if (host == null || cookieDomain == null) return false;
        String dn = cookieDomain.startsWith(".") ? cookieDomain.substring(1) : cookieDomain;
        boolean domainOk =
                host.equalsIgnoreCase(dn) ||
                host.toLowerCase().endsWith("." + dn.toLowerCase());
        if (!domainOk) return false;

        // path 默认 "/"
        String cp = (cookiePath == null || cookiePath.isEmpty()) ? "/" : cookiePath;
        String rp = (requestPath == null || requestPath.isEmpty()) ? "/" : requestPath;
        return rp.startsWith(cp);
    }

    // ==================== 工具方法 ====================

    /**
     * 把非 200 的响应体完整写到本地 tmp/proxy-errors/ 下，以便离线打开看清学校
     * 的错误页到底说了什么。learned the hard way —— log 摘要 500 字节远不够用。
     * <p>
     * 返回落盘文件的绝对路径（供 log 打印）；写盘失败返回 "(dump failed)"。
     */
    private String dumpErrorBody(int status, String url, byte[] body, Header contentType) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("tmp", "proxy-errors");
            java.nio.file.Files.createDirectories(dir);

            // 文件名：yyyyMMdd-HHmmss.SSS_<status>_<url-tail-safe>.html
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss.SSS").format(new java.util.Date());
            String tail = url == null ? "unknown" :
                    url.replaceAll(".*[?/&]([^?&/=]+)=?([^&?]*)$", "$1-$2")
                       .replaceAll("[^A-Za-z0-9_.-]", "_");
            if (tail.length() > 60) tail = tail.substring(0, 60);
            String ext = (contentType != null && contentType.getValue() != null
                    && contentType.getValue().toLowerCase().contains("html")) ? ".html" : ".bin";
            java.nio.file.Path f = dir.resolve(ts + "_" + status + "_" + tail + ext);

            if (body != null && body.length > 0) {
                java.nio.file.Files.write(f, body);
            } else {
                java.nio.file.Files.write(f, ("(empty body, status=" + status + ")").getBytes(StandardCharsets.UTF_8));
            }
            return f.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("dumpErrorBody 落盘失败: {}", e.getMessage());
            return "(dump failed)";
        }
    }

    /**
     * 嗅探响应体是否为"伪 200 登录页"。WebVPN / 博达 CMS 在 cookie 无效时，
     * 常常返回 200 + 登录表单 HTML，这里取前 2KB 按关键字识别，避免把垃圾 HTML
     * 当附件交给前端导致 openDocument 打开失败且看不到原因。
     */
    private boolean looksLikeLoginHtml(byte[] body, Header contentType) {
        if (body == null || body.length == 0) return false;
        // 真二进制（PDF/Office/Zip）首字节通常非 HTML；简单启发式：只检查 text/html 或无 CT 的响应
        if (contentType != null) {
            String ct = contentType.getValue();
            if (ct != null && !ct.toLowerCase().contains("html")) return false;
        }
        int n = Math.min(body.length, 2048);
        String head = new String(body, 0, n, StandardCharsets.UTF_8).toLowerCase();
        if (!head.contains("<html") && !head.contains("<!doctype")) return false;
        // 登录墙典型关键字（中文需先 UTF-8 decode）
        return head.contains("login")
                || head.contains("signin")
                || head.contains("authn")
                || head.contains("请登录")
                || head.contains("登录页")
                || head.contains("统一身份认证");
    }

    /**
     * 安全检查：只允许代理学校域名
     */
    private boolean isAllowedDomain(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url).getHost();
            return host != null && (
                    host.endsWith("sztu.edu.cn") ||
                            host.endsWith("webvpn.sztu.edu.cn")
            );
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 URL 推断 Content-Type
     */
    private String guessContentType(String url) {
        String lower = url.toLowerCase();
        // 先检查扩展名
        int lastDot = lower.lastIndexOf('.');
        int lastSlash = lower.lastIndexOf('/');
        int lastQuestion = lower.indexOf('?');
        if (lastDot > lastSlash && (lastQuestion < 0 || lastDot < lastQuestion)) {
            String ext = lower.substring(lastDot + 1, lastQuestion > 0 ? lastQuestion : lower.length());
            String mime = MIME_MAP.get(ext);
            if (mime != null) return mime;
        }
        // download.jsp → 默认二进制流
        if (lower.contains("download.jsp") || lower.contains("attachment")) {
            return "application/octet-stream";
        }
        // __local/ 路径通常是图片
        if (lower.contains("__local/") || lower.contains("_upload/")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    /**
     * 从 URL 提取文件名
     */
    private String extractFilenameFromUrl(String url) {
        // 先去掉查询参数
        int q = url.indexOf('?');
        String path = q > 0 ? url.substring(0, q) : url;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
        }
        return "download";
    }

    /**
     * 提取 origin（scheme + host）
     */
    private String extractOrigin(String url) {
        try {
            URI uri = URI.create(url);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://" + uri.getHost() + port;
        } catch (Exception e) {
            return "";
        }
    }
}