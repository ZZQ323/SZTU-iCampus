package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.domain.event.AcademicSessionReadyEvent;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.parser.CrouseParser;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.SmartCookieConverter;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.dto.SmartRequest;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.edu.sztui.base.domain.model.login.SchoolAPIs.*;

/**
 * 教务系统服务实现
 * <p>
 * 使用 SmartHttpClient 替代 Playwright，通过重定向链获取教务系统 cookie，
 * 然后发送 AJAX 请求获取课表数据。
 */
@Slf4j
@Service
public class AcademicServiceImpl implements AcademicService {

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private CrouseParser crouseParser;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    /** 教务 SSO 链诊断开关。出问题时打开，产出 infos/runtime-trace/academic-init/... 全跳 HTML */
    @Value("${acdm.trace.enabled:false}")
    private boolean traceEnabled;

    /** 诊断产物目录（相对后端进程工作目录）*/
    @Value("${acdm.trace.dir:infos/runtime-trace/academic-init}")
    private String traceDir;

    /** 手动循环模式的最大跳数（和 SmartHttpClient 的 MAX_REDIRECTS 一致）*/
    private static final int TRACE_MAX_HOPS = 25;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";

    // ==================== 初始化教务系统 ====================

    @Override
    public LoginResultsVo init() {
        TokenMessage ctx = UserContext.getContext();
        String userId = ctx != null ? ctx.getUserId() : null;
        String cookiesJson = ctx != null ? ctx.getSchoolCookiesJson() : null;

        if (!StringUtils.hasText(cookiesJson)) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "请先登录学校系统",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // ⭐ 短路：前端传来的 cookies 里已经有 jwxt 子域 cookies → 之前已 init 过且仍有效
        // 跑过的 SSO 链是"一次性"的：第二次跑会带上 jsxsd 专属 cookies 干扰 IDP 路由，
        // 反而落到 /idp/AuthnEngine 失败。已初始化就直接复用。
        // 真正失效的场景由 AcademicInboxFastScheduler 自愈 + 用户"刷新会话"两层兜底。
        List<SmartCookie> incoming = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        boolean alreadyInitialized = incoming.stream().anyMatch(c ->
                c.getDomain() != null && c.getDomain().toLowerCase().contains("jwxt-sztu-edu-cn"));
        if (alreadyInitialized) {
            log.info("用户 {} 已持有 jwxt 子域 cookies，跳过教务初始化", userId);
            // 仍然发事件，让 listener 跑一次 acdm 拉取（用户主动调 init 多半是想看新内容）
            if (StringUtils.hasText(userId)) {
                eventPublisher.publishEvent(new AcademicSessionReadyEvent(this, userId));
            }
            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(true);
            ret.setCookiesJson(cookiesJson);
            ret.setUserId(userId);
            return ret;
        }

        String updatedJson = initInternal(userId, cookiesJson);
        if (updatedJson == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "教务系统初始化失败",
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }

        // 通知下游：jwxt cookies 已就绪，可以开始爬 acdm-* 数据源
        if (StringUtils.hasText(userId)) {
            eventPublisher.publishEvent(new AcademicSessionReadyEvent(this, userId));
        }

        LoginResultsVo ret = new LoginResultsVo();
        ret.setLogined(true);
        ret.setCookiesJson(updatedJson);
        ret.setUserId(userId);
        return ret;
    }

    @Override
    public String initInternal(String userId, String cookiesJson) {
        if (!StringUtils.hasText(cookiesJson)) {
            log.warn("initInternal: 空 cookies, userId={}", userId);
            return null;
        }

        // 配置 acdm.trace.enabled=true 时走诊断路径：手动循环 SSO 链，每跳落盘 HTML +
        // 写 index.txt + 前后 cookies 快照，方便离线对照 HAR 找分歧点。
        // 默认关；只在排查问题时打开，别常年挂着跑（IO 成本非零且会写一堆文件）。
        if (traceEnabled) {
            return initInternalTraced(userId, cookiesJson);
        }

        log.info("用户 {} 初始化教务系统会话", userId);

        // 1. 用传入的网关 cookies 构建 session
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        SmartSession session = smartHttpClient.newSession(cookies);

        try {
            // 2. 访问教务系统入口，SmartHttpClient 自动跟随重定向链
            //    302 → 302 → ... → 最终落地教务主页
            //    过程中 session 自动积累教务系统的 cookies
            SmartRequest request = SmartRequest.builder()
                    .url(AcdmGatewayURL)
                    .method("GET")
                    .timeoutSeconds(smartHttpClient.getSlowTimeoutSeconds())
                    .followRedirects(true)
                    .build();

            SmartResponse response = smartHttpClient.execute(request, session);

            log.info("教务系统重定向完成: finalUrl={}, redirects={}",
                    response.getFinalUrl(), response.getRedirectCount());

            String finalUrl = response.getFinalUrl();
            // ⭐ 校验最终落地：如果重定向链被 WebVPN 打回 IDP 登录页，说明根域会话已死。
            // 这种情况下 session 里积累的只是匿名预登录 cookies，写回 Redis 反而污染原数据。
            // 只有确实落到 /jsxsd/ 才算成功。
            if (finalUrl == null || !finalUrl.contains("/jsxsd/")) {
                log.warn("教务初始化最终未落地 jsxsd, finalUrl={} —— webvpn 根会话可能已过期，保留原 cookies 不覆盖",
                        finalUrl);
                return null;
            }
            if (finalUrl.contains(AcdmSwitchPort)) {
                log.info("检测到选课期间");
            }
        } catch (Exception e) {
            log.error("教务系统初始化失败: userId={}, error={}", userId, e.getMessage(), e);
            return null;
        }

        // 3. 保存更新后的 cookies 到 Redis（供爬虫引擎使用）
        List<SmartCookie> updatedCookies = session.getCookies();
        if (StringUtils.hasText(userId)) {
            authSessionCacheUtil.saveOrUpdateSessionCookie(userId, updatedCookies);
        }

        log.info("用户 {} 教务系统 Cookie 已更新，共 {} 个", userId, updatedCookies.size());
        return JSON.toJSONString(updatedCookies);
    }

    // ==================== Tier 1 诊断：手动循环 SSO 链 + 全跳落盘 ====================

    /**
     * 诊断版 initInternal：接管 SmartHttpClient 的自动重定向，改为手动一跳一执行，
     * 每跳 log + dump HTML + 记 cookies 变化。产物落到
     * {@code {acdm.trace.dir}/{YYYYMMDD-HHmmss}_{userId}/}，供离线对照 HAR。
     * <p>
     * 为什么不扩 SmartHttpClient 而是本地重做：这里只关心 Location 3xx 链路（HAR 里每
     * 一跳都是 302+Location，覆盖率 100%）。若某天碰到 meta-refresh / JS 跳，我们会在
     * dump 的 HTML 里看到，再回头修；现在硬上"全模式重定向解析"等于重写重定向框架，
     * 调试收益远低于成本。
     * <p>
     * 刻意不改线上 followRedirects=true 主路径，避免手工循环 bug 带坏正常用户。
     */
    private String initInternalTraced(String userId, String cookiesJson) {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String safeUserId = userId == null ? "anonymous" : userId.replaceAll("[^A-Za-z0-9_-]", "_");
        Path traceRoot = Paths.get(traceDir, ts + "_" + safeUserId);

        List<SmartCookie> initialCookies = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        SmartSession session = smartHttpClient.newSession(initialCookies);

        try {
            Files.createDirectories(traceRoot);
            Files.writeString(traceRoot.resolve("cookies-before.json"),
                    JSON.toJSONString(session.getCookies()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[TRACE] 无法创建诊断目录 {}: {}", traceRoot, e.getMessage());
        }

        log.info("[TRACE] 开始手动 SSO 链 userId={} 产物目录={}", userId, traceRoot.toAbsolutePath());

        StringBuilder index = new StringBuilder("hop\tmethod\tstatus\tURL\tbodyLen\tnewCookies\tnextLocation\n");
        String currentUrl = AcdmGatewayURL;
        String method = "GET";
        String referer = null;
        String finalUrl = null;
        Set<String> seenCookieKeys = snapshotCookieKeys(session.getCookies());

        for (int hop = 1; hop <= TRACE_MAX_HOPS; hop++) {
            try {
                SmartRequest req = SmartRequest.builder()
                        .url(currentUrl)
                        .method(method)
                        .timeoutSeconds(smartHttpClient.getSlowTimeoutSeconds())
                        .followRedirects(false)   // 手动接管
                        .referer(referer)
                        .build();

                SmartResponse res = smartHttpClient.execute(req, session);

                int status = res.getStatusCode();
                String body = res.getBody() != null ? res.getBody() : "";
                String location = res.getLocationHeader();

                // diff cookies（新增的 name=value 列表）
                List<SmartCookie> after = session.getCookies();
                List<String> newlyAdded = new ArrayList<>();
                for (SmartCookie c : after) {
                    String key = c.getDomain() + "|" + c.getName();
                    if (!seenCookieKeys.contains(key)) {
                        newlyAdded.add(c.getName() + "=" + truncate(c.getValue(), 40)
                                + "(@" + c.getDomain() + ")");
                        seenCookieKeys.add(key);
                    }
                }

                String hostPath = safePathTail(currentUrl);
                String dumpName = String.format("%02d_%s_%d_%s.html", hop, method, status, hostPath);
                try {
                    Files.writeString(traceRoot.resolve(dumpName), body, StandardCharsets.UTF_8);
                } catch (Exception ignore) { /* 尽力而为 */ }

                log.info("[TRACE] hop={} {} {} -> status={} location={} bodyLen={} newCookies={}",
                        hop, method, currentUrl, status,
                        location == null ? "(none)" : location,
                        body.length(), newlyAdded);

                index.append(hop).append('\t')
                        .append(method).append('\t')
                        .append(status).append('\t')
                        .append(currentUrl).append('\t')
                        .append(body.length()).append('\t')
                        .append(newlyAdded).append('\t')
                        .append(location == null ? "" : location)
                        .append('\n');

                finalUrl = currentUrl;

                // 继续跳转条件：3xx + 有 Location。其它（2xx、4xx、5xx）视为终态。
                if (!res.isRedirect() || location == null || location.isEmpty()) {
                    break;
                }

                referer = currentUrl;
                currentUrl = absolutize(currentUrl, location);
                // RFC 7231：302/303 跟进一律用 GET；307/308 保留方法但 SSO 链基本不出现，简化处理
                method = "GET";
            } catch (Exception e) {
                log.error("[TRACE] hop={} 异常 url={} err={}", hop, currentUrl, e.getMessage(), e);
                index.append("!!! hop ").append(hop).append(" exception: ").append(e.getMessage()).append('\n');
                break;
            }
        }

        // 落盘 index + 最终 cookies
        try {
            index.append("\nfinalUrl=").append(finalUrl).append('\n');
            Files.writeString(traceRoot.resolve("index.txt"), index.toString(), StandardCharsets.UTF_8);
            Files.writeString(traceRoot.resolve("cookies-after.json"),
                    JSON.toJSONString(session.getCookies()), StandardCharsets.UTF_8);
        } catch (Exception ignore) { /* 尽力而为 */ }

        // 判定成败（和非 trace 路径同样规则）
        if (finalUrl == null || !finalUrl.contains("/jsxsd/")) {
            log.warn("[TRACE] 教务初始化未落地 jsxsd，finalUrl={}，全跳产物见 {}",
                    finalUrl, traceRoot.toAbsolutePath());
            return null;
        }
        if (finalUrl.contains(AcdmSwitchPort)) {
            log.info("[TRACE] 检测到选课期间");
        }

        List<SmartCookie> updatedCookies = session.getCookies();
        if (StringUtils.hasText(userId)) {
            authSessionCacheUtil.saveOrUpdateSessionCookie(userId, updatedCookies);
        }
        log.info("[TRACE] 用户 {} 教务系统 Cookie 已更新，共 {} 个，产物 {}",
                userId, updatedCookies.size(), traceRoot.toAbsolutePath());
        return JSON.toJSONString(updatedCookies);
    }

    /** 把 SmartCookie 列表的 (domain|name) 做成 set，后续每跳做 diff 用。 */
    private static Set<String> snapshotCookieKeys(List<SmartCookie> cookies) {
        Set<String> s = new LinkedHashSet<>();
        if (cookies != null) {
            for (SmartCookie c : cookies) {
                s.add(c.getDomain() + "|" + c.getName());
            }
        }
        return s;
    }

    /** Location 可能是相对 URL，按 base 拼成绝对 URL（保留 base 的 scheme+host+port）*/
    private static String absolutize(String baseUrl, String location) {
        if (location == null) return null;
        if (location.startsWith("http://") || location.startsWith("https://")) return location;
        try {
            URI base = URI.create(baseUrl);
            if (location.startsWith("/")) {
                return base.getScheme() + "://" + base.getRawAuthority() + location;
            }
            // 相对路径：base 的目录 + location
            String path = base.getRawPath() == null ? "/" : base.getRawPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return base.getScheme() + "://" + base.getRawAuthority() + dir + location;
        } catch (Exception e) {
            return location;
        }
    }

    /** 把 URL 尾部剪成可做文件名的短 ID。例："auth-sztu-edu-cn-s_idp_AuthnEngine" */
    private static String safePathTail(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "unknown" : u.getHost();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String combined = host + path;
            String safe = combined.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.length() > 80) safe = safe.substring(0, 80);
            return safe;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 获取课表 ====================

    @Override
    public CourseTableVo getCrouseTable(CrouseTableQuery query) {
        TokenMessage ctx = UserContext.getContext();
        String userId = ctx != null ? ctx.getUserId() : null;
        String cookiesJson = ctx != null ? ctx.getSchoolCookiesJson() : null;

        if (!StringUtils.hasText(cookiesJson)) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "请先登录学校系统",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // 1. 用前端传来的 cookies（应该已包含教务系统 cookies）
        List<SmartCookie> cookies = SmartCookieConverter.jsonToSmartCookies(cookiesJson);
        SmartSession session = smartHttpClient.newSession(cookies);

        SmartResponse response;
        try {
            // ⭐ 不加 ?sf_request_type=ajax —— 教务系统需要返回完整 HTML（含 #timetable）
            String scheduleUrl = AcdmScheduleTableURL;

            // ⭐ 统一使用普通表单 POST（和浏览器行为一致），不用 AJAX headers
            Map<String, String> formData = new HashMap<>();
            formData.put("cj0701id", "");
            formData.put("zc", query != null && query.getWeek() != null ? query.getWeek() : "");
            formData.put("demo", "");
            formData.put("xnxq01id", query != null && query.getSemester() != null ? query.getSemester() : "");
            formData.put("sfFD", "1");
            formData.put("wkbkc", "1");
            formData.put("kbjcmsid", "EB5693B95B204102B2E28C5624C6E9ED");

            response = smartHttpClient.post(scheduleUrl, formData, session);

            if (!response.isSuccess()) {
                log.error("课程表请求失败: status={}, userId={}", response.getStatusCode(), userId);
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "课程表获取失败，请稍后重试",
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("课程表请求出错: userId={}, error={}", userId, e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "课程表获取失败：" + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }

        // 2. 更新 cookies
        List<SmartCookie> updatedCookies = session.getCookies();
        if (StringUtils.hasText(userId)) {
            authSessionCacheUtil.saveOrUpdateSessionCookie(userId, updatedCookies);
        }

        // 3. 解析课表 HTML
        return crouseParser.parseCourseTable(response.getBody());
    }
}
