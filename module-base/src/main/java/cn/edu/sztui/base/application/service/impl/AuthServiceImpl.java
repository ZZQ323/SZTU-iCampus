package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.external.UserLoginEvent;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.base.domain.model.login.LoginType;
import cn.edu.sztui.base.infrastructure.convertor.CharacterConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.URLPraser;
import cn.edu.sztui.base.infrastructure.util.praser.UserInfoPraser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.checker.SchoolSessionChecker;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.sztui.base.domain.model.login.SchoolAPIs.*;

/**
 * 认证服务实现（精简版）
 * <p>
 * 核心改动：
 * <ul>
 *   <li>使用 {@link SchoolSessionChecker} 统一检测会话状态</li>
 *   <li>删除状态缓存逻辑，每次 getStatus 都实时检查</li>
 *   <li>删除 Cookie 过期预测逻辑</li>
 *   <li>logout 时保存新 Cookie 而非清空</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 状态查询 ====================

    /**
     * 获取登录状态（实时检查，无缓存）
     * <p>
     * 每次调用都会访问学校网关，通过重定向结果判断登录状态。
     * 同时起到 Cookie 保活的作用。
     */
    @Override
    public LoginStatusVo getStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.info("用户 {} 查询登录状态", wxId);

        // 直接调用 doRefreshCookies，不做缓存
        LoginResultsVo result = doRefreshCookies(wxId, authSessionCacheUtil.getSession(wxId));

        // 转换为 LoginStatusVo
        return LoginStatusVo.from(result);
    }

    @Override
    public boolean getSessionStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        return authSessionCacheUtil.hasSession(wxId);
    }

    @Override
    public List<String> getPossibleUsrId() {
        TokenMessage tokenMessage = UserContext.getContext();
        ProxySession session = authSessionCacheUtil.getSession(tokenMessage.getOpenId());
        if (Objects.isNull(session)) {
            return Collections.emptyList();
        }
        return session.getUserIds();
    }

    // ==================== 会话管理 ====================

    /**
     * 初始化会话（强制重建 Cookie）
     * <p>
     * 先删除旧的 ProxySession，再重新获取 Cookie。
     */
    @Override
    public LoginResultsVo initSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("用户 {} 初始化会话（强制重建）", wxId);

        // ⭐ 强制删除旧的 ProxySession
        authSessionCacheUtil.deleteSession(wxId);

        // 用空 session 重新获取 Cookie
        return doRefreshCookies(wxId, null);
    }

    /**
     * 刷新会话（仅刷新已有会话）
     */
    @Override
    public LoginResultsVo refreshSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.info("用户 {} 刷新会话", wxId);

        // 检查是否有会话
        ProxySession session = authSessionCacheUtil.getSession(wxId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话不存在，请先初始化",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        // 执行刷新
        LoginResultsVo result = doRefreshCookies(wxId, session);

        // 检查刷新结果：如果未登录，抛出 403
        if (!result.isLogined()) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "学校会话已过期",
                    ResultCodeEnum.FORBIDDEN.getCode()  // 403
            );
        }

        return result;
    }

    @Override
    @Deprecated
    public LoginResultsVo refresh() {
        return initSession();
    }

    // ==================== 核心：Cookie 刷新逻辑 ====================

    /**
     * 刷新 Cookie 并检测登录状态
     * <p>
     * 使用 {@link SchoolSessionChecker} 统一判断登录状态。
     */
    private LoginResultsVo doRefreshCookies(String wxId, ProxySession session) {
        LoginResultsVo ret = new LoginResultsVo();
        ret.setLogined(false);

        log.info("🔄 doRefreshCookies: openId={}, hasSession={}", wxId, session != null);

        try (SmartSession smartSession = createSmartSession(session)) {

            log.info("🍪 doRefreshCookies 开始时有 {} 个 Cookie", smartSession.getCookies().size());

            // 访问网关起始页，自动跟随所有重定向
            SmartResponse response = smartHttpClient.get(gatewayStartURL, smartSession);

            String finalUrl = response.getFinalUrl();
            log.info("最终 URL: {}, 重定向次数: {}", finalUrl, response.getRedirectCount());

            // 打印重定向链，用于调试
            if (log.isDebugEnabled()) {
                log.debug("重定向链: {}", response.getRedirectChain());
            }

            log.info("🍪 doRefreshCookies 请求后有 {} 个 Cookie", smartSession.getCookies().size());
            log.info("📍 SchoolSessionChecker 状态: {}", SchoolSessionChecker.getStatusDescription(response));

            // ⭐ 使用 SchoolSessionChecker 统一判断
            if (SchoolSessionChecker.isLoggedIn(response)) {
                // 已登录
                ret.setLogined(true);
                UserContext.getContext().setLoginTime(System.currentTimeMillis());

                // 解析用户信息
                UserInfoPraser.extractByRegex(ret, response.getBody());

                // 发布登录成功事件
                eventPublisher.publishEvent(new UserLoginEvent(
                        this, wxId,
                        ret.getUserId(),
                        ret.getRealName()
                ));

            } else if (SchoolSessionChecker.isRedirectedToLogin(response)) {
                // 未登录，检测支持的登录方式
                List<String> typeStrings = SchoolSessionChecker.detectLoginTypes(response);
                List<LoginType> loginTypes = typeStrings.stream()
                        .map(LoginType::valueOf)
                        .collect(Collectors.toList());
                ret.setLoginTypes(loginTypes);
                log.info("检测到登录方式: {}", typeStrings);

            } else {
                // 未知状态，尝试从响应体判断
                String body = response.getBody();
                if (body != null) {
                    if (body.contains("bmportal") || body.contains("userInfo") || body.contains("个人中心")) {
                        ret.setLogined(true);
                        UserInfoPraser.extractByRegex(ret, body);
                        log.info("根据页面内容判断为已登录状态");
                    } else {
                        ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                        log.info("根据页面内容判断为未登录状态，默认 SMS");
                    }
                } else {
                    ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                }
                log.warn("未知的最终页面 URL: {}", finalUrl);
            }

            log.info("解析到用户信息: userId={}, realName={}, logined={}",
                    ret.getUserId(), ret.getRealName(), ret.isLogined());

            // 保存 Cookies
            saveSessionCookies(wxId, smartSession);

            return ret;

        } catch (SmartHttpException e) {
            log.error("SmartHttp 请求失败: {}", e.getMessage());
            if (e.isRetryable()) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "学校服务器响应超时，请稍后重试",
                        ResultCodeEnum.GATEWAY_TIMEOUT.getCode()
                );
            }
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话刷新失败: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("会话刷新出现错误: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话刷新出现错误: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
    }

    // ==================== 登录/登出 ====================

    @Override
    public void getSms(String usrId) {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("📱 获取短信验证码: openId={}, userId={}", wxId, usrId);

        try (SmartSession smartSession = smartHttpClient.newSession()) {

            log.info("🍪 getSms 开始时有 0 个 Cookie（新会话）");

            // 第一步：访问 WebVPN 入口，建立完整的会话链路
            String redirectUri = "https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal";
            String thdLoginUrl = "https://webvpn.sztu.edu.cn/public/thdportal_login?redirect_uri=" +
                    java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8);

            log.info("📍 第一步：访问 WebVPN 入口建立会话链路...");

            SmartResponse loginPageRes = smartHttpClient.get(thdLoginUrl, smartSession);

            log.info("📍 登录页面最终 URL: {}", loginPageRes.getFinalUrl());
            log.info("🍪 收集到 {} 个 Cookie", smartSession.getCookies().size());

            // 第二步：发送短信验证码请求
            log.info("📍 第二步：发送短信验证码请求...");

            Map<String, String> formData = new HashMap<>();
            formData.put("j_username", CharacterConverter.toSBC(usrId));

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", loginPageRes.getFinalUrl());
            headers.put("Origin", URLPraser.extractOrigin(gatewaySmsURL));

            SmartResponse response = smartHttpClient.postAjax(
                    gatewaySmsURL + "?sf_request_type=ajax",
                    formData,
                    smartSession,
                    headers
            );

            log.info("📱 短信接口响应: status={}, body={}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

            // 验证响应
            if (!response.isSuccess()) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "发送短信验证码失败，服务器返回错误",
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // 检查响应内容
            String body = response.getBody();
            if (body == null || body.isEmpty()) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "发送短信验证码失败，服务器无响应",
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // 保存会话 Cookie（供后续登录使用）
            saveSessionCookies(wxId, smartSession);

            log.info("📱 短信验证码请求成功");

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取短信验证码失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "获取短信验证码失败: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
    }

    @Override
    public LoginResultsVo loginFrame(LoginRequestCommand cmd) {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("🔐 用户 {} 登录学校系统: userId={}, loginType={}",
                wxId, cmd.getUserId(), cmd.getLoginType());

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // 第一步：AJAX 验证
            Map<String, String> verifyFormData = buildAjaxVerifyFormData(cmd);
            Map<String, String> verifyHeaders = new HashMap<>();
            String refererUrl = (cmd.getLoginType() == LoginType.SMS) ? gatewayFirstURL : gatewaySecondURL;
            verifyHeaders.put("Referer", refererUrl);
            verifyHeaders.put("Origin", URLPraser.extractOrigin(gatewayLoginSubmitURL));
            verifyHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            verifyHeaders.put("Accept", "*/*");
            verifyHeaders.put("X-Requested-With", "XMLHttpRequest");
            verifyHeaders.put("Origin", URLPraser.extractOrigin(gatewayLoginSubmitURL));

            String ajaxUrl = gatewayLoginSubmitURL + "?sf_request_type=ajax";
            SmartResponse ajaxResponse = smartHttpClient.postAjax(ajaxUrl, verifyFormData, smartSession, verifyHeaders);

            log.info("🔐 AJAX 验证响应: {}", ajaxResponse.getBody());

            // 第二步：表单提交
            String submitUrl = getFormSubmitUrl(cmd.getLoginType());
            Map<String, String> formData = buildFormSubmitData(cmd);

            SmartResponse ajaxRes = smartHttpClient.postAjax(
                    submitUrl + "?sf_request_type=ajax",
                    formData,
                    smartSession,
                    verifyHeaders
            );

            log.info("🔐 表单提交后最终 URL: {}", ajaxRes.getFinalUrl());

            // 第三步：检查登录结果
            LoginResultsVo result = new LoginResultsVo();

            if (SchoolSessionChecker.isLoggedIn(ajaxRes)) {
                result.setLogined(true);
                UserInfoPraser.extractByRegex(result, ajaxRes.getBody());

                // 绑定用户
                authSessionCacheUtil.sessionLoginBind(
                        wxId,
                        result.getUserId() != null ? result.getUserId() : cmd.getUserId(),
                        convertToPlaywrightCookies(smartSession.getCookies())
                );

                // 发布登录事件
                eventPublisher.publishEvent(new UserLoginEvent(
                        this, wxId,
                        result.getUserId(),
                        result.getRealName()
                ));

                log.info("🔐 登录成功: userId={}, realName={}", result.getUserId(), result.getRealName());

            } else {
                result.setLogined(false);
                List<String> typeStrings = SchoolSessionChecker.detectLoginTypes(ajaxRes);
                result.setLoginTypes(typeStrings.stream()
                        .map(LoginType::valueOf)
                        .collect(Collectors.toList()));

                // 保存 Cookie（即使登录失败也保存，下次可能成功）
                saveSessionCookies(wxId, smartSession);

                log.warn("🔐 登录失败，最终 URL: {}", ajaxRes.getFinalUrl());
            }

            return result;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "登录失败: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
    }

    /**
     * 登出学校系统
     * <p>
     * ⭐ 关键改动：保存登出后的新 Cookie，而非清空
     */
    @Override
    public LoginResultsVo logout() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("🚪 用户 {} 登出学校系统", wxId);

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // 访问登出 URL，跟随重定向
            SmartResponse response = smartHttpClient.get(logoutSubmitURL, smartSession);

            log.info("🚪 登出后最终 URL: {}", response.getFinalUrl());
            log.info("🍪 登出后有 {} 个 Cookie", smartSession.getCookies().size());

            // ⭐ 保存登出后的新 Cookie（用于下次登录可选择密码方式）
            List<com.microsoft.playwright.options.Cookie> newCookies =
                    convertToPlaywrightCookies(smartSession.getCookies());
            authSessionCacheUtil.sessionLogoutBind(wxId, newCookies);

            // 构建返回结果
            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);

            // 检测登出后支持的登录方式
            List<String> typeStrings = SchoolSessionChecker.detectLoginTypes(response);
            ret.setLoginTypes(typeStrings.stream()
                    .map(LoginType::valueOf)
                    .collect(Collectors.toList()));

            log.info("🚪 登出成功，可用登录方式: {}", typeStrings);

            return ret;

        } catch (Exception e) {
            log.error("登出失败: {}", e.getMessage());
            // 即使失败也更新本地状态
            authSessionCacheUtil.sessionLogoutBind(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
            return ret;
        }
    }

    // ==================== 表单构建方法 ====================

    private Map<String, String> buildAjaxVerifyFormData(LoginRequestCommand cmd) {
        Map<String, String> formData = new HashMap<>();
        formData.put("j_username", cmd.getUserId());
        formData.put("j_checkcode", "验证码");
        formData.put("op", "login");

        if (cmd.getLoginType() == LoginType.SMS) {
            formData.put("sms_checkcode", cmd.getSmsCode());
            formData.put("spAuthChainCode", spAuthChainCodeSMS);
        } else if (cmd.getLoginType() == LoginType.PASSWORD) {
            formData.put("j_password", cmd.getPassword());
            formData.put("spAuthChainCode", spAuthChainCodePASSWORD);
        }

        return formData;
    }

    private Map<String, String> buildFormSubmitData(LoginRequestCommand cmd) {
        Map<String, String> formData = new HashMap<>();
        formData.put("j_username", cmd.getUserId());
        formData.put("j_checkcode", "验证码");
        formData.put("op", "login");

        if (cmd.getLoginType() == LoginType.SMS) {
            formData.put("sms_checkcode", cmd.getSmsCode());
            formData.put("spAuthChainCode", spAuthChainCodeSMS);
        } else if (cmd.getLoginType() == LoginType.PASSWORD) {
            formData.put("j_password", cmd.getPassword());
            formData.put("spAuthChainCode", spAuthChainCodePASSWORD);
        }

        return formData;
    }

    private String getFormSubmitUrl(LoginType loginType) {
        if (loginType == LoginType.SMS) {
            return A4tLoginSMSRedirectURL;
        } else {
            return A4tLoginPASSWORDRedirectURL;
        }
    }

    // ==================== 辅助方法 ====================

    private SmartSession createSmartSession(ProxySession proxySession) {
        if (proxySession == null || proxySession.getCookiesJson() == null
                || proxySession.getCookiesJson().isEmpty()) {
            log.debug("ProxySession 为空或无 Cookie，创建新的空 Session");
            return smartHttpClient.newSession();
        }

        try {
            List<SmartCookie> cookies = parseCookiesFromJson(proxySession.getCookiesJson());
            log.info("从缓存加载了 {} 个 Cookie", cookies.size());
            return smartHttpClient.newSession(cookies);
        } catch (Exception e) {
            log.warn("解析 Cookie JSON 失败: {}", e.getMessage());
            return smartHttpClient.newSession();
        }
    }

    private List<SmartCookie> parseCookiesFromJson(String cookiesJson) throws Exception {
        JsonNode arrayNode = objectMapper.readTree(cookiesJson);

        if (!arrayNode.isArray()) {
            log.warn("Cookie JSON 不是数组格式");
            return Collections.emptyList();
        }

        List<SmartCookie> cookies = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            String name = node.path("name").asText(null);
            String value = node.path("value").asText(null);
            String domain = node.path("domain").asText(null);
            String path = node.path("path").asText("/");

            if (domain == null || domain.isEmpty()) {
                String url = node.path("url").asText(null);
                if (url != null && !url.isEmpty()) {
                    try {
                        domain = new java.net.URI(url).getHost();
                    } catch (Exception ignored) {
                    }
                }
            }

            if (domain == null || domain.isEmpty()) {
                domain = "webvpn.sztu.edu.cn";
            }

            SmartCookie cookie = SmartCookie.builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path(path)
                    .httpOnly(node.path("httpOnly").asBoolean(false))
                    .secure(node.path("secure").asBoolean(false))
                    .build();

            if (cookie.getName() != null && cookie.getValue() != null) {
                cookies.add(cookie);
            }
        }

        return cookies;
    }

    private void saveSessionCookies(String wxId, SmartSession smartSession) {
        List<SmartCookie> smartCookies = smartSession.getCookies();
        log.info("💾 saveSessionCookies: 准备保存 {} 个 SmartCookie", smartCookies.size());

        List<com.microsoft.playwright.options.Cookie> playwrightCookies =
                convertToPlaywrightCookies(smartCookies);

        authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, playwrightCookies);
        log.info("💾 已保存 Cookie");
    }

    private List<com.microsoft.playwright.options.Cookie> convertToPlaywrightCookies(List<SmartCookie> smartCookies) {
        return smartCookies.stream()
                .map(SmartCookie::toPlaywright)
                .collect(Collectors.toList());
    }
}