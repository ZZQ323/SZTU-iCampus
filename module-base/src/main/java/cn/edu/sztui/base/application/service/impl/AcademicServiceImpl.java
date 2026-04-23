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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
