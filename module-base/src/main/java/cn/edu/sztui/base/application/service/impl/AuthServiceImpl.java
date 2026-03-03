package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.external.UserLoginEvent;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.base.domain.model.loginhandle.HandleCluster;
import cn.edu.sztui.base.domain.model.loginhandle.LoginHandle;
import cn.edu.sztui.base.domain.model.loginhandle.LoginType;
import cn.edu.sztui.base.infrastructure.convertor.CharacterConverter;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.URLPraser;
import cn.edu.sztui.base.infrastructure.util.praser.UserInfoPraser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.browserpool.PlaywrightBrowserPool;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cn.edu.sztui.base.domain.model.SchoolAPIs.*;

/**
 * 认证服务实现（修复版 - 添加用户信息解析）
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private PlaywrightBrowserPool browserPool;
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
    public LoginStatusVo getStatus()
    {
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

        // 3. 缓存未命中，使用【默认超时】获取真实状态
        LoginResultsVo result = doRefreshCookies(wxId, authSessionCacheUtil.getSession(wxId),
                browserPool.getSlowTimeoutSeconds());

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

        int slowTimeout = browserPool.getSlowTimeoutSeconds();
        log.info("用户 {} 初始化会话（首次加载，使用长超时 {}s）", wxId, slowTimeout);

        // 清除旧缓存，强制重新获取
        authSessionCacheUtil.invalidateStatusCache(wxId);
        authSessionCacheUtil.clearSessionCookies(wxId);

        // 使用【长超时】执行 Playwright 流程
        LoginResultsVo result = doRefreshCookies(wxId, null, slowTimeout);

        // 更新状态缓存
        LoginStatusVo status = LoginStatusVo.from(result);
        authSessionCacheUtil.cacheStatus(wxId, status);

        return result;
    }

    @Override
    public LoginResultsVo refreshSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.info("用户 {} 刷新会话（使用默认超时）", wxId);

        // 1. 检查是否有会话
        ProxySession session = authSessionCacheUtil.getSession(wxId);
        if (session == null) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话不存在，请先初始化",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        // 2. 检查 Cookie 是否可能过期
        if (authSessionCacheUtil.isCookiePossiblyExpired(wxId)) {
            log.warn("Cookie 可能已过期，建议重新初始化: openId={}", wxId);
        }

        // 3. 使用【默认超时】执行刷新
        LoginResultsVo result = doRefreshCookies(wxId, session, browserPool.getDefaultTimeoutSeconds());

        // 4. 检查刷新结果
        if (!result.isLogined()) {
            authSessionCacheUtil.invalidateStatusCache(wxId);
            authSessionCacheUtil.clearSessionCookies(wxId);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话已过期，请重新登录",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // 5. 更新状态缓存
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

    private LoginResultsVo doRefreshCookies(String wxId, ProxySession session, int timeoutSeconds)
    {
        return browserPool.executeWithContext(context -> {
            if (!Objects.isNull(session) && session.getCookiesJson() != null && !session.getCookiesJson().isEmpty()) {
                context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));
            }

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            try {
                Page page = context.newPage();
                Response response = page.waitForResponse(
                        resp -> resp.url().equals(gatewayFirstEndURL)
                                || resp.url().equals(gatewaySecondEndURL)
                                || resp.url().equals(acdemAdminSysGatewayStartURL),
                        () -> page.navigate(gatewayStartURL)
                );

                if (response.url().equals(acdemAdminSysGatewayStartURL)) {
                    ret.setLogined(true);
                    UserContext.getContext().setLoginTime(System.currentTimeMillis());
                    // 解析用户信息
                    UserInfoPraser.extractByRegex(ret, response.text());
                } else if (response.url().equals(gatewayFirstEndURL)) {
                    ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                } else if (response.url().equals(gatewaySecondEndURL)) {
                    List<LoginType> typeLists = new ArrayList<>();
                    typeLists.add(LoginType.SMS);
                    typeLists.add(LoginType.PASSWORD);
                    ret.setLoginTypes(typeLists);
                } else {
                    throw new BusinessException(
                            SysReturnCode.BASE_PROXY.getCode(),
                            "未知的页面URL：" + response.url(),
                            ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                    );
                }
            } catch (com.microsoft.playwright.TimeoutError e) {
                log.error("Playwright 超时（{}s）: {}", timeoutSeconds, e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "学校服务器响应超时，请稍后重试",
                        ResultCodeEnum.GATEWAY_TIMEOUT.getCode()
                );
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("会话刷新出现错误：{}", e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "会话刷新出现错误：" + e.getMessage(),
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }
            log.info("解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());
            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());
            return ret;
        }, timeoutSeconds);
    }

    // ==================== 登录/登出 ====================

    @Override
    public void getSms(String usrId) {
        browserPool.executeWithContext(context -> {
            TokenMessage tokenMessage = UserContext.getContext();
            String wxId = tokenMessage.getOpenId();

            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }

            APIRequestContext req = context.request();
            FormData formData = FormData.create();
            formData.set("j_username", CharacterConverter.toSBC(usrId));

            APIResponse res = req.post(smsURL + "?sf_request_type=ajax",
                    RequestOptions.create()
                            .setForm(formData)
                            .setHeader("X-Requested-With", "XMLHttpRequest")
                            .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                            .setHeader("Referer", gatewayFirstEndURL)
                            .setHeader("Origin", URLPraser.extractOrigin(smsURL))
                            .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
            );

            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());
            return null;
        });
    }

    @Override
    public LoginResultsVo loginFrame(LoginRequestCommand cmd) {
        return browserPool.executeWithContext(context -> {
            TokenMessage tokenMessage = UserContext.getContext();
            String wxId = tokenMessage.getOpenId();

            LoginResultsVo ret = new LoginResultsVo();
            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }

            // ============ 第一步：AJAX 验证 ============
            LoginHandle loginHandle = handleCluster.getSpringLoginHandle(cmd.getLoginType());
            APIResponse ajaxRes = loginHandle.loginVerification(context, cmd);

            String ajaxBody = ajaxRes.text();
            JsonNode json = objectMapper.readTree(ajaxBody);
            boolean loginFailed = json.path("loginFailed").asBoolean(true);

            if (loginFailed) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "登录验证失败: " + ajaxBody,
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // ============ 第二步：模拟表单提交 ============
            APIResponse formRes = loginHandle.loginRedirect(context, cmd);
            // log.info("表单提交后 cookies: {}", context.cookies());

            Page page = context.newPage();
            Response response = page.waitForResponse(
                    resp -> resp.url().equals(acdemAdminSysGatewayStartURL),
                    () -> page.navigate(gatewayStartURL)
            );
            // ============ 访问最终页面获取用户信息 ============
            if (response.url().equals(acdemAdminSysGatewayStartURL)) {
                UserInfoPraser.extractByRegex(ret, response.text());
                log.info("从最终页面解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());
            }

            UserInfoPraser.extractByRegex(ret, formRes.text());
            log.info("解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());

            // ============ 第三步：更新网关的 Cookie ============
            authSessionCacheUtil.sessionLoginBind(wxId, cmd.getUserId(), context.cookies());

            // ============ 第四步：使状态缓存失效 ============
            authSessionCacheUtil.invalidateStatusCache(wxId);

            // ============ 第五步：通知 CookieSourceManager 用户登录 ============
            // 设置 Cookie 来源
            announcementCacheUtil.setActiveSourceOpenId(wxId);
            // ⭐ 发布登录成功事件（异步触发公告初始化）
            eventPublisher.publishEvent(new UserLoginEvent(
                    this,wxId,
                    ret.getUserId(),
                    ret.getRealName()
            ));

            log.info("已发布用户登录事件: openId={}", wxId);

            ret.setWxId(wxId);
            ret.setLogined(true);
            return ret;
        },browserPool.getSlowTimeoutSeconds());
    }

    @Override
    public LoginResultsVo logout(LoginRequestCommand cmd) {
        return browserPool.executeWithContext(context -> {
            TokenMessage tokenMessage = UserContext.getContext();
            String wxId = tokenMessage.getOpenId();

            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }

            APIRequestContext req = context.request();
            APIResponse res = req.get(logoutURL);

            authSessionCacheUtil.sessionLogoutBind(wxId);
            authSessionCacheUtil.invalidateStatusCache(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;
        });
    }
}