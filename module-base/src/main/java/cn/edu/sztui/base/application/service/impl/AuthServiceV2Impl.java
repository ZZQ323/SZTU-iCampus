package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.external.UserLoginEvent;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.base.domain.model.loginhandle.HandleCluster;
import cn.edu.sztui.base.domain.model.loginhandle.LoginType;
import cn.edu.sztui.base.infrastructure.convertor.CharacterConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.URLPraser;
import cn.edu.sztui.base.infrastructure.util.praser.UserInfoPraser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
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

import static cn.edu.sztui.base.domain.model.SchoolAPIs.*;

/**
 * 认证服务 V2 实现（基于 SmartHttpClient，无浏览器）
 * <p>
 * 文件位置：module-base/src/main/java/cn/edu/sztui/base/application/service/impl/AuthServiceV2Impl.java
 * <p>
 * 【特性】：
 * - 使用纯 HTTP 请求，自动处理重定向
 * - 200 并发仅需 ~50MB 内存（对比 Playwright 需要 10-30GB）
 * - 支持 Location Header、Meta Refresh、JS 重定向
 */
@Slf4j
@Service("authServiceV2")
public class AuthServiceV2Impl implements AuthService {

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

    @Autowired
    private HandleCluster handleCluster;

    // ==================== 状态查询 ====================

    @Override
    public LoginStatusVo getStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.debug("用户 {} 查询登录状态", wxId);

        // 1. 优先从缓存读取状态
        LoginStatusVo cachedStatus = authSessionCacheUtil.getCachedStatus(wxId);
        if (cachedStatus != null) {
            log.debug("命中状态缓存: openId={}, logined={}", wxId, cachedStatus.isLogined());
            return cachedStatus;
        }

        // 2. 检查 Cookie 是否可能过期
        if (authSessionCacheUtil.isCookiePossiblyExpired(wxId)) {
            log.info("Cookie 可能已过期，清除旧 Cookie: openId={}", wxId);
            authSessionCacheUtil.clearSessionCookies(wxId);
        }

        // 3. 缓存未命中，获取真实状态
        LoginResultsVo result = doRefreshCookies(wxId, authSessionCacheUtil.getSession(wxId));

        // 4. 转换并缓存
        LoginStatusVo status = LoginStatusVo.from(result);
        status.setCookieExpiringSoon(authSessionCacheUtil.isCookieExpiringSoon(wxId));
        authSessionCacheUtil.cacheStatus(wxId, status);

        return status;
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

    @Override
    public LoginResultsVo initSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("用户 {} 初始化会话（SmartHttpClient V2）", wxId);

        // 清除旧缓存，强制重新获取
        authSessionCacheUtil.invalidateStatusCache(wxId);
        authSessionCacheUtil.clearSessionCookies(wxId);

        // 执行刷新
        LoginResultsVo result = doRefreshCookies(wxId, null);

        // 更新状态缓存
        LoginStatusVo status = LoginStatusVo.from(result);
        authSessionCacheUtil.cacheStatus(wxId, status);

        return result;
    }

    @Override
    public LoginResultsVo refreshSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.info("用户 {} 刷新会话", wxId);

        // 1. 检查是否有会话
        ProxySession session = authSessionCacheUtil.getSession(wxId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话不存在，请先初始化",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        // 2. 执行刷新
        LoginResultsVo result = doRefreshCookies(wxId, session);

        // 3. 检查刷新结果
        if (!result.isLogined()) {
            authSessionCacheUtil.invalidateStatusCache(wxId);
            authSessionCacheUtil.clearSessionCookies(wxId);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话已过期，请重新登录",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // 4. 更新状态缓存
        LoginStatusVo status = LoginStatusVo.from(result);
        authSessionCacheUtil.cacheStatus(wxId, status);

        return result;
    }

    @Override
    @Deprecated
    public LoginResultsVo refresh() {
        return initSession();
    }

    // ==================== 核心：Cookie 刷新逻辑 ====================

    private LoginResultsVo doRefreshCookies(String wxId, ProxySession session) {
        LoginResultsVo ret = new LoginResultsVo();
        ret.setLogined(false);

        try (SmartSession smartSession = createSmartSession(session)) {

            // 访问网关起始页，自动跟随所有重定向
            SmartResponse response = smartHttpClient.get(gatewayStartURL, smartSession);

            String finalUrl = response.getFinalUrl();
            log.debug("最终 URL: {}, 重定向次数: {}", finalUrl, response.getRedirectCount());

            // 根据最终 URL 判断登录状态
            if (finalUrl.contains(acdemAdminSysGatewayStartURL) || finalUrl.contains("/bmportal/index.portal")) {
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

            } else if (finalUrl.contains(gatewayFirstEndURL) || finalUrl.contains("/idp/authcenter/ActionAuthChain")) {
                // 未登录，需要 SMS
                ret.setLoginTypes(Collections.singletonList(LoginType.SMS));

            } else if (finalUrl.contains(gatewaySecondEndURL)) {
                // 未登录，支持 SMS + PASSWORD
                List<LoginType> typeLists = new ArrayList<>();
                typeLists.add(LoginType.SMS);
                typeLists.add(LoginType.PASSWORD);
                ret.setLoginTypes(typeLists);

            } else {
                // 未知状态，尝试从响应体判断
                String body = response.getBody();
                if (body != null) {
                    if (body.contains("登录") || body.contains("login")) {
                        ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                    } else if (body.contains("bmportal") || body.contains("userInfo")) {
                        ret.setLogined(true);
                        UserInfoPraser.extractByRegex(ret, body);
                    }
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

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // 构建表单数据
            Map<String, String> formData = new HashMap<>();
            formData.put("j_username", CharacterConverter.toSBC(usrId));

            // 构建额外请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", gatewayFirstEndURL);
            headers.put("Origin", URLPraser.extractOrigin(smsURL));

            // 发送 AJAX 请求获取短信
            SmartResponse response = smartHttpClient.postAjax(
                    smsURL + "?sf_request_type=ajax",
                    formData,
                    smartSession,
                    headers
            );

            log.debug("短信请求响应: {}", response.getStatusCode());

            // 保存 Cookies
            saveSessionCookies(wxId, smartSession);

        } catch (Exception e) {
            log.error("获取短信验证码失败: {}", e.getMessage());
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

        LoginResultsVo ret = new LoginResultsVo();

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // ============ 第一步：AJAX 验证 ============
            Map<String, String> verifyFormData = buildVerifyFormData(cmd);
            Map<String, String> verifyHeaders = new HashMap<>();
            verifyHeaders.put("Referer", gatewayFirstEndURL);
            verifyHeaders.put("Origin", URLPraser.extractOrigin(loginURL));

            SmartResponse ajaxRes = smartHttpClient.postAjax(
                    loginURL + "?sf_request_type=ajax",
                    verifyFormData,
                    smartSession,
                    verifyHeaders
            );

            String ajaxBody = ajaxRes.getBody();
            log.debug("Login AJAX 响应: {}", ajaxBody);

            JsonNode json = objectMapper.readTree(ajaxBody);
            boolean loginFailed = json.path("loginFailed").asBoolean(true)
                    || ajaxBody.contains("当前界面遇到了一些问题");

            if (loginFailed) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "登录验证失败: " + ajaxBody,
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // ============ 第二步：表单提交 ============
            Map<String, String> loginFormData = buildLoginFormData(cmd);
            String loginSubmitURL = A4tLoginSMSFormActionURL;
            SmartResponse formRes = smartHttpClient.post(loginSubmitURL, loginFormData, smartSession);

            log.debug("表单提交后最终 URL: {}", formRes.getFinalUrl());

            // ============ 第三步：访问最终页面获取用户信息 ============
            SmartResponse finalRes = smartHttpClient.get(gatewayStartURL, smartSession);

            if (finalRes.getFinalUrl().contains(acdemAdminSysGatewayStartURL)
                    || finalRes.getFinalUrl().contains("/bmportal/index.portal")) {
                UserInfoPraser.extractByRegex(ret, finalRes.getBody());
                log.info("从最终页面解析到用户信息: userId={}, realName={}",
                        ret.getUserId(), ret.getRealName());
            }

            // 也尝试从表单响应中解析
            UserInfoPraser.extractByRegex(ret, formRes.getBody());
            log.info("解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());

            // ============ 第四步：保存 Cookies ============
            authSessionCacheUtil.sessionLoginBind(wxId, cmd.getUserId(),
                    convertToPlaywrightCookies(smartSession.getCookies()));

            // ============ 第五步：使状态缓存失效 ============
            authSessionCacheUtil.invalidateStatusCache(wxId);

            // ============ 第六步：发布登录成功事件 ============
            announcementCacheUtil.setActiveSourceOpenId(wxId);
            eventPublisher.publishEvent(new UserLoginEvent(
                    this, wxId,
                    ret.getUserId(),
                    ret.getRealName()
            ));

            log.info("已发布用户登录事件: openId={}", wxId);

            ret.setWxId(wxId);
            ret.setLogined(true);
            return ret;

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

    @Override
    public LoginResultsVo logout(LoginRequestCommand cmd) {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // 访问登出 URL
            smartHttpClient.get(logoutURL, smartSession);

            // 清除会话
            authSessionCacheUtil.sessionLogoutBind(wxId);
            authSessionCacheUtil.invalidateStatusCache(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;

        } catch (Exception e) {
            log.error("登出失败: {}", e.getMessage());
            // 即使失败也清除本地会话
            authSessionCacheUtil.sessionLogoutBind(wxId);
            authSessionCacheUtil.invalidateStatusCache(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 ProxySession 创建 SmartSession
     */
    private SmartSession createSmartSession(ProxySession proxySession) {
        if (proxySession == null || proxySession.getCookiesJson() == null
                || proxySession.getCookiesJson().isEmpty()) {
            return smartHttpClient.newSession();
        }

        try {
            // cookiesJson 是 JSON 字符串，需要先解析
            List<SmartCookie> cookies = parseCookiesFromJson(proxySession.getCookiesJson());
            return smartHttpClient.newSession(cookies);
        } catch (Exception e) {
            log.warn("解析 Cookie JSON 失败: {}", e.getMessage());
            return smartHttpClient.newSession();
        }
    }

    /**
     * 从 JSON 字符串解析 Cookies
     * <p>
     * JSON 格式（与 Playwright Cookie 兼容）:
     * [
     * {"name": "xxx", "value": "yyy", "domain": "zzz", "path": "/", ...},
     * ...
     * ]
     */
    private List<SmartCookie> parseCookiesFromJson(String cookiesJson) throws Exception {
        JsonNode arrayNode = objectMapper.readTree(cookiesJson);

        if (!arrayNode.isArray()) {
            log.warn("Cookie JSON 不是数组格式");
            return Collections.emptyList();
        }

        List<SmartCookie> cookies = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            SmartCookie cookie = SmartCookie.builder()
                    .name(node.path("name").asText(null))
                    .value(node.path("value").asText(null))
                    .domain(node.path("domain").asText(null))
                    .path(node.path("path").asText("/"))
                    .httpOnly(node.path("httpOnly").asBoolean(false))
                    .secure(node.path("secure").asBoolean(false))
                    .build();

            if (cookie.getName() != null && cookie.getValue() != null) {
                cookies.add(cookie);
            }
        }

        return cookies;
    }

    /**
     * 保存 SmartSession 的 Cookies 到缓存
     */
    private void saveSessionCookies(String wxId, SmartSession smartSession) {
        List<com.microsoft.playwright.options.Cookie> playwrightCookies =
                convertToPlaywrightCookies(smartSession.getCookies());
        authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, playwrightCookies);
    }

    /**
     * 将 SmartCookie 转换为 Playwright Cookie（复用现有的缓存逻辑）
     */
    private List<com.microsoft.playwright.options.Cookie> convertToPlaywrightCookies(List<SmartCookie> smartCookies) {
        return smartCookies.stream()
                .map(SmartCookie::toPlaywright)
                .collect(Collectors.toList());
    }

    /**
     * 构建验证表单数据
     */
    private Map<String, String> buildVerifyFormData(LoginRequestCommand cmd) {
        Map<String, String> formData = new HashMap<>();
        formData.put("j_username", CharacterConverter.toSBC(cmd.getUserId()));

        if (cmd.getLoginType() == LoginType.SMS) {
            formData.put("j_checkcode", cmd.getSmsCode());
        } else if (cmd.getLoginType() == LoginType.PASSWORD) {
            formData.put("j_password", cmd.getPassword());
        }

        return formData;
    }

    /**
     * 构建登录表单数据
     */
    private Map<String, String> buildLoginFormData(LoginRequestCommand cmd) {
        Map<String, String> formData = new HashMap<>();
        formData.put("j_username", CharacterConverter.toSBC(cmd.getUserId()));

        if (cmd.getLoginType() == LoginType.SMS) {
            formData.put("j_checkcode", cmd.getSmsCode());
        } else if (cmd.getLoginType() == LoginType.PASSWORD) {
            formData.put("j_password", cmd.getPassword());
        }

        // 其他必要字段
        formData.put("sf_request_type", "ajax");

        return formData;
    }
}
