package cn.edu.sztui.common.util.smarthttp;

import cn.edu.sztui.common.util.smarthttp.redirect.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能 HTTP 客户端实现
 * 
 * 自动处理各种重定向，无需浏览器
 * 
 * 文件位置：module-common/src/main/java/cn/edu/sztui/common/util/smarthttp/SmartHttpClientImpl.java
 */
@Slf4j
@Service
public class SmartHttpClientImpl implements SmartHttpClient {
    
    private CloseableHttpClient httpClient;
    private List<RedirectHandler> redirectHandlers;
    
    /** 最大重定向次数 */
    private static final int MAX_REDIRECTS = 25;
    
    /** 默认超时（秒） */
    @Value("${smart-http.timeout-seconds:30}")
    private int defaultTimeoutSeconds;
    
    /** 慢请求超时（秒） */
    @Value("${smart-http.slow-timeout-seconds:90}")
    private int slowTimeoutSeconds;
    
    /** 最大连接数 */
    @Value("${smart-http.max-connections:500}")
    private int maxConnections;
    
    /** 每个路由最大连接数 */
    @Value("${smart-http.max-connections-per-route:100}")
    private int maxConnectionsPerRoute;
    
    /** User-Agent */
    @Value("${smart-http.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0}")
    private String userAgent;
    
    @PostConstruct
    public void init() throws Exception {
        // 1. 创建 SSL 上下文（忽略证书错误）
        var sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();
        
        var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();
        
        // 2. 创建连接池
        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnectionsPerRoute)
                .setSSLSocketFactory(sslSocketFactory)
                .build();
        
        // 3. 创建 HttpClient（禁用内置重定向，我们自己处理）
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .disableRedirectHandling()  // ⭐ 关键：禁用内置重定向
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(defaultTimeoutSeconds))
                        .setResponseTimeout(Timeout.ofSeconds(defaultTimeoutSeconds))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();
        
        // 4. 注册重定向处理器（按优先级排序）
        this.redirectHandlers = new ArrayList<>();
        this.redirectHandlers.add(new LocationHeaderHandler());        // 优先级 1
        this.redirectHandlers.add(new MetaRefreshHandler());           // 优先级 2
        this.redirectHandlers.add(new DataParameterRedirectHandler()); // 优先级 5
        this.redirectHandlers.add(new PortalEntryRedirectHandler());   // 优先级 8
        this.redirectHandlers.add(new GLinesRedirectHandler());        // 优先级 10
        this.redirectHandlers.add(new JsRedirectHandler());            // 优先级 20
        
        // 按优先级排序
        this.redirectHandlers.sort(Comparator.comparingInt(RedirectHandler::getPriority));
        
        log.info("SmartHttpClient 初始化完成 - maxConnections: {}, timeout: {}s, slowTimeout: {}s",
                maxConnections, defaultTimeoutSeconds, slowTimeoutSeconds);
    }
    
    @Override
    public SmartResponse get(String url, SmartSession session) throws SmartHttpException {
        return execute(SmartRequest.get(url), session);
    }
    
    @Override
    public SmartResponse post(String url, Map<String, String> formData, SmartSession session) throws SmartHttpException {
        return execute(SmartRequest.post(url, formData), session);
    }
    
    @Override
    public SmartResponse postAjax(String url, Map<String, String> formData, SmartSession session, 
                                   Map<String, String> extraHeaders) throws SmartHttpException {
        SmartRequest request = SmartRequest.ajax(url, formData);
        if (extraHeaders != null) {
            request.getHeaders().putAll(extraHeaders);
        }
        return executeNoRedirect(request, session);
    }
    
    @Override
    public SmartResponse execute(SmartRequest request, SmartSession session) throws SmartHttpException {
        String currentUrl = request.getUrl();
        List<String> redirectChain = new ArrayList<>();
        redirectChain.add(currentUrl);
        
        int timeoutSeconds = request.getTimeoutSeconds() > 0 
                ? request.getTimeoutSeconds() 
                : defaultTimeoutSeconds;
        
        String method = request.getMethod();
        Map<String, String> formData = request.getFormData();
        String referer = request.getReferer();
        
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            log.debug("请求 [{}]: {} {}", i + 1, method, currentUrl);
            
            // 执行请求
            RawResponse raw = doRequest(currentUrl, method, formData, session, 
                                        request.getHeaders(), referer, timeoutSeconds);
            
            // 更新 Session 的 Cookies
            if (raw.setCookieHeaders != null && !raw.setCookieHeaders.isEmpty()) {
                session.parseAndAddCookies(raw.setCookieHeaders, currentUrl);
            }
            
            // 如果不跟随重定向，直接返回
            if (!request.isFollowRedirects()) {
                return buildResponse(raw, currentUrl, redirectChain, i);
            }
            
            // 检测重定向
            String nextUrl = detectRedirect(currentUrl, raw);
            
            if (nextUrl == null) {
                // 没有重定向，返回最终结果
                return buildResponse(raw, currentUrl, redirectChain, i);
            }
            
            // 有重定向，继续跟随
            log.debug("检测到重定向: {} -> {}", currentUrl, nextUrl);
            referer = currentUrl;
            currentUrl = nextUrl;
            redirectChain.add(currentUrl);
            
            // 重定向后使用 GET 方法（遵循 HTTP 规范）
            method = "GET";
            formData = null;
        }
        
        throw SmartHttpException.tooManyRedirects(MAX_REDIRECTS);
    }
    
    @Override
    public SmartResponse executeNoRedirect(SmartRequest request, SmartSession session) throws SmartHttpException {
        request.setFollowRedirects(false);
        return execute(request, session);
    }
    
    /**
     * 检测重定向（责任链模式）
     */
    private String detectRedirect(String currentUrl, RawResponse raw) {
        for (RedirectHandler handler : redirectHandlers) {
            String nextUrl = handler.detectRedirect(currentUrl, raw.statusCode, raw.headers, raw.body);
            if (nextUrl != null) {
                log.debug("[{}] 检测到重定向", handler.getName());
                return nextUrl;
            }
        }
        return null;
    }
    
    /**
     * 执行原始 HTTP 请求
     */
    private RawResponse doRequest(String url, String method, Map<String, String> formData,
                                   SmartSession session, Map<String, String> extraHeaders,
                                   String referer, int timeoutSeconds) throws SmartHttpException {
        try {
            HttpUriRequestBase request;
            
            if ("POST".equalsIgnoreCase(method) && formData != null && !formData.isEmpty()) {
                HttpPost post = new HttpPost(url);
                List<NameValuePair> params = formData.entrySet().stream()
                        .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());
                post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
                post.setHeader("Content-Type", "application/x-www-form-urlencoded");
                request = post;
            } else {
                request = new HttpGet(url);
            }
            
            // 设置超时
            request.setConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(timeoutSeconds))
                    .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
                    .build());
            
            // 设置基本头
            request.setHeader("User-Agent", userAgent);
            request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            request.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            request.setHeader("Accept-Encoding", "gzip, deflate, br");
            request.setHeader("Connection", "keep-alive");
            
            // 设置 Referer
            if (referer != null) {
                request.setHeader("Referer", referer);
            }
            
            // 设置额外头
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    request.setHeader(entry.getKey(), entry.getValue());
                }
            }
            
            // 设置 Cookie
            String cookieHeader = session.buildCookieHeader(url);
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                request.setHeader("Cookie", cookieHeader);
                log.debug("发送请求: {} {} (带 {} 字符的 Cookie)", method, url, cookieHeader.length());
            } else {
                log.warn("发送请求: {} {} (无 Cookie!)", method, url);
            }
            
            // 执行请求
            try (var response = httpClient.execute(request)) {
                // 提取响应头
                Map<String, String> headers = new HashMap<>();
                for (Header header : response.getHeaders()) {
                    // 只保留第一个值（简化处理）
                    if (!headers.containsKey(header.getName())) {
                        headers.put(header.getName(), header.getValue());
                    }
                }
                
                // 提取 Set-Cookie 头（可能有多个）
                List<String> setCookieHeaders = new ArrayList<>();
                for (Header header : response.getHeaders("Set-Cookie")) {
                    setCookieHeaders.add(header.getValue());
                }
                
                // 提取响应体
                String body = null;
                if (response.getEntity() != null) {
                    body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                }
                
                return new RawResponse(response.getCode(), body, headers, setCookieHeaders);
            }
            
        } catch (java.net.SocketTimeoutException | org.apache.hc.core5.http.ConnectionClosedException e) {
            throw SmartHttpException.timeout(url);
        } catch (java.io.IOException e) {
            throw SmartHttpException.connectionFailed(url, e);
        } catch (Exception e) {
            throw new SmartHttpException("请求失败: " + url + " - " + e.getMessage(), e);
        }
    }
    
    private SmartResponse buildResponse(RawResponse raw, String finalUrl, 
                                         List<String> redirectChain, int redirectCount) {
        return SmartResponse.builder()
                .statusCode(raw.statusCode)
                .body(raw.body)
                .finalUrl(finalUrl)
                .redirectChain(redirectChain)
                .headers(raw.headers)
                .redirectCount(redirectCount)
                .build();
    }
    
    @Override
    public SmartSession newSession() {
        return new SmartSessionImpl();
    }
    
    @Override
    public SmartSession newSession(List<SmartCookie> cookies) {
        return new SmartSessionImpl(cookies);
    }
    
    @Override
    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }
    
    @Override
    public int getSlowTimeoutSeconds() {
        return slowTimeoutSeconds;
    }
    
    @PreDestroy
    public void destroy() {
        if (httpClient != null) {
            try {
                httpClient.close();
                log.info("SmartHttpClient 已销毁");
            } catch (Exception e) {
                log.error("关闭 HttpClient 失败", e);
            }
        }
    }
    
    /**
     * 原始响应（内部使用）
     */
    private record RawResponse(
            int statusCode,
            String body,
            Map<String, String> headers,
            List<String> setCookieHeaders
    ) {}
}
