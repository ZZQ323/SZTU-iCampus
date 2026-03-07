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
 * 
 * 【修复版 - 参考 UsernameSmsImpl.java】：
 * - 修复 SMS 登录表单字段：sms_checkcode（不是 j_checkcode）
 * - 添加必要字段：op=login, spAuthChainCode
 * - 添加 /por/ 页面处理
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
                    if (body.contains("j_username") || body.contains("登录") || body.contains("login")) {
                        // 包含登录表单，未登录状态
                        ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                        log.info("根据页面内容判断为未登录状态");
                    } else if (body.contains("bmportal") || body.contains("userInfo") || body.contains("个人中心")) {
                        // 包含门户内容，已登录状态
                        ret.setLogined(true);
                        UserInfoPraser.extractByRegex(ret, body);
                        log.info("根据页面内容判断为已登录状态");
                    } else {
                        // 默认为未登录
                        ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
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

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            log.info("🍪 getSms 开始时有 {} 个 Cookie", smartSession.getCookies().size());

            // ⭐ 关键修复：先访问登录页面，建立完整的会话上下文
            // 这一步会收集所有必要的 Cookie（WebVPN SESSION、认证系统 SESSION 等）
            log.info("📍 第一步：访问登录页面建立会话上下文...");
            SmartResponse loginPageRes = smartHttpClient.get(gatewayFirstEndURL, smartSession);
            log.info("📍 登录页面最终 URL: {}, Cookie 数量: {}", 
                    loginPageRes.getFinalUrl(), smartSession.getCookies().size());
            
            // 打印收集到的 Cookie
            for (SmartCookie c : smartSession.getCookies()) {
                log.debug("  🍪 收集到 Cookie: {}={} (domain={})", 
                        c.getName(), 
                        c.getValue().length() > 10 ? c.getValue().substring(0, 10) + "..." : c.getValue(),
                        c.getDomain());
            }

            // ⭐ 第二步：发送短信验证码请求
            log.info("📍 第二步：发送短信验证码请求...");
            
            // 构建表单数据 - 参考 UsernameSmsImpl
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

            log.info("📱 短信请求响应: status={}", response.getStatusCode());
            
            // 检查响应
            String body = response.getBody();
            if (body != null && body.contains("错误") && body.contains("问题")) {
                log.error("❌ 短信请求返回错误页面");
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "获取短信验证码失败，请重新初始化",
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            log.info("🍪 getSms 请求后有 {} 个 Cookie", smartSession.getCookies().size());

            // 保存 Cookies
            saveSessionCookies(wxId, smartSession);
            log.info("💾 已保存 {} 个 Cookie 到缓存", smartSession.getCookies().size());

        } catch (BusinessException e) {
            throw e;
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

        // ========== 调试：检查 ProxySession ==========
        ProxySession cachedSession = authSessionCacheUtil.getSession(wxId);
        if (cachedSession == null) {
            log.error("❌ ProxySession 为空! openId={}", wxId);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话不存在，请先获取短信验证码",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }
        
        String cookiesJson = cachedSession.getCookiesJson();
        log.info("📦 ProxySession.cookiesJson 长度: {}", 
                cookiesJson != null ? cookiesJson.length() : 0);
        // ========== 调试结束 ==========

        try (SmartSession smartSession = createSmartSession(cachedSession)) {

            // ========== 调试：检查 SmartSession ==========
            List<SmartCookie> loadedCookies = smartSession.getCookies();
            log.info("🍪 SmartSession 中加载了 {} 个 Cookie", loadedCookies.size());
            for (SmartCookie c : loadedCookies) {
                log.debug("  🍪 {}={} (domain={}, path={})", 
                        c.getName(), 
                        c.getValue().length() > 20 ? c.getValue().substring(0, 20) + "..." : c.getValue(),
                        c.getDomain(), 
                        c.getPath());
            }
            // ========== 调试结束 ==========

            // ⭐ 关键修复：先访问登录页面，确保会话上下文正确
            // 这一步会验证现有 Cookie 是否有效，并可能收集新的 Cookie
            String refererUrl = (cmd.getLoginType() == LoginType.SMS) ? gatewayFirstEndURL : gatewaySecondEndURL;
            log.info("📍 第零步：访问登录页面确认会话...");
            SmartResponse preCheckRes = smartHttpClient.get(refererUrl, smartSession);
            log.info("📍 登录页面最终 URL: {}, Cookie 数量: {}", 
                    preCheckRes.getFinalUrl(), smartSession.getCookies().size());
            
            // 检查是否到达了登录页面
            String preCheckBody = preCheckRes.getBody();
            if (preCheckBody != null && (preCheckBody.contains("j_username") || preCheckBody.contains("sms_checkcode"))) {
                log.info("✅ 成功到达登录页面");
            } else {
                log.warn("⚠️ 可能没有到达登录页面，继续尝试登录...");
            }

            // ============ 第一步：AJAX 验证 ============
            Map<String, String> verifyFormData = buildAjaxVerifyFormData(cmd);
            Map<String, String> verifyHeaders = new HashMap<>();
            
            verifyHeaders.put("Referer", refererUrl);
            verifyHeaders.put("Origin", URLPraser.extractOrigin(loginURL));
            verifyHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            verifyHeaders.put("Accept", "*/*");
            verifyHeaders.put("X-Requested-With", "XMLHttpRequest");

            log.info("📤 发送 AJAX 验证请求: url={}", loginURL + "?sf_request_type=ajax");
            log.debug("📤 表单数据: {}", verifyFormData);

            SmartResponse ajaxRes = smartHttpClient.postAjax(
                    loginURL + "?sf_request_type=ajax",
                    verifyFormData,
                    smartSession,
                    verifyHeaders
            );

            String ajaxBody = ajaxRes.getBody();
            log.info("📥 AJAX 响应: status={}, bodyLength={}", 
                    ajaxRes.getStatusCode(), ajaxBody != null ? ajaxBody.length() : 0);
            
            // 打印响应内容用于调试
            if (ajaxBody != null) {
                if (ajaxBody.length() < 500) {
                    log.debug("📥 响应内容: {}", ajaxBody);
                } else {
                    log.debug("📥 响应前500字符: {}", ajaxBody.substring(0, 500));
                }
            }

            // ⭐ 关键修复：检查响应是否为 HTML（被重定向）
            if (ajaxBody == null || ajaxBody.trim().startsWith("<")) {
                log.error("❌ AJAX 请求返回了 HTML 而非 JSON");
                log.error("❌ 这通常意味着 Cookie 未正确发送或会话上下文错误");
                
                // 打印当前 session 中的 Cookie 用于调试
                log.error("❌ 当前 session 中有 {} 个 Cookie:", smartSession.getCookies().size());
                for (SmartCookie c : smartSession.getCookies()) {
                    log.error("  ❌ {}={} (domain={})", c.getName(), 
                            c.getValue().length() > 10 ? c.getValue().substring(0, 10) + "..." : c.getValue(),
                            c.getDomain());
                }
                
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "登录会话已失效，请重新获取短信验证码",
                        ResultCodeEnum.UNAUTHORIZED.getCode()
                );
            }

            JsonNode json = objectMapper.readTree(ajaxBody);
            boolean loginFailed = json.path("loginFailed").asBoolean(true)
                    || ajaxBody.contains("当前界面遇到了一些问题");

            if (loginFailed) {
                String errorMsg = json.path("message").asText(json.path("msg").asText("验证失败"));
                log.error("❌ 登录验证失败: {}", errorMsg);
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "登录验证失败: " + errorMsg,
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }
            
            log.info("✅ AJAX 验证成功");

            // ============ 第二步：表单提交 ============
            String formSubmitUrl = getFormSubmitUrl(cmd.getLoginType());
            Map<String, String> loginFormData = buildFormSubmitData(cmd);
            
            Map<String, String> formHeaders = new HashMap<>();
            formHeaders.put("Referer", refererUrl);
            formHeaders.put("Origin", URLPraser.extractOrigin(loginURL));
            
            log.info("📤 提交登录表单: url={}", formSubmitUrl);
            
            SmartResponse formRes = smartHttpClient.post(formSubmitUrl, loginFormData, smartSession);

            log.info("📥 表单提交后最终 URL: {}", formRes.getFinalUrl());

            // ============ 第三步：访问最终页面获取用户信息 ============
            SmartResponse finalRes = smartHttpClient.get(gatewayStartURL, smartSession);

            if (finalRes.getFinalUrl().contains(acdemAdminSysGatewayStartURL)
                    || finalRes.getFinalUrl().contains("/bmportal/index.portal")) {
                UserInfoPraser.extractByRegex(ret, finalRes.getBody());
                log.info("✅ 从最终页面解析到用户信息: userId={}, realName={}",
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

            log.info("✅ 登录成功，已发布用户登录事件: openId={}", wxId);

            ret.setWxId(wxId);
            ret.setLogined(true);
            return ret;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ 登录失败: {}", e.getMessage(), e);
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

    // ==================== 表单构建方法（⭐关键修复） ====================

    /**
     * 构建 AJAX 验证表单数据
     * 
     * 参考 UsernameSmsImpl.loginVerification() 和 UsernamePasswordImpl.loginVerification()
     */
    private Map<String, String> buildAjaxVerifyFormData(LoginRequestCommand cmd) {
        Map<String, String> formData = new HashMap<>();
        formData.put("j_username", cmd.getUserId());  // 不需要 toSBC 转换
        formData.put("j_checkcode", "验证码");        // 固定值
        formData.put("op", "login");                  // ⭐ 必须字段
        
        if (cmd.getLoginType() == LoginType.SMS) {
            // SMS 登录：使用 sms_checkcode（不是 j_checkcode！）
            formData.put("sms_checkcode", cmd.getSmsCode());
            formData.put("spAuthChainCode", spAuthChainCodeSMS);  // ⭐ 必须字段
        } else if (cmd.getLoginType() == LoginType.PASSWORD) {
            // 密码登录
            formData.put("j_password", cmd.getPassword());
            formData.put("spAuthChainCode", spAuthChainCodePASSWORD);  // ⭐ 必须字段
        }
        
        return formData;
    }

    /**
     * 构建表单提交数据
     * 
     * 参考 UsernameSmsImpl.loginRedirect() 和 UsernamePasswordImpl.loginRedirect()
     */
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

    /**
     * 根据登录类型获取表单提交 URL
     */
    private String getFormSubmitUrl(LoginType loginType) {
        if (loginType == LoginType.SMS) {
            return A4tLoginSMSFormActionURL;
        } else {
            return A4tLoginPASSWORDFormActionURL;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 ProxySession 创建 SmartSession
     */
    private SmartSession createSmartSession(ProxySession proxySession) {
        if (proxySession == null || proxySession.getCookiesJson() == null
                || proxySession.getCookiesJson().isEmpty()) {
            log.debug("ProxySession 为空或无 Cookie，创建新的空 Session");
            return smartHttpClient.newSession();
        }

        try {
            List<SmartCookie> cookies = parseCookiesFromJson(proxySession.getCookiesJson());
            log.info("从缓存加载了 {} 个 Cookie", cookies.size());
            
            // 打印 Cookie 详情用于调试
            if (log.isDebugEnabled()) {
                for (SmartCookie c : cookies) {
                    log.debug("  Cookie: name={}, domain={}, path={}", 
                            c.getName(), c.getDomain(), c.getPath());
                }
            }
            
            return smartHttpClient.newSession(cookies);
        } catch (Exception e) {
            log.warn("解析 Cookie JSON 失败: {}", e.getMessage());
            return smartHttpClient.newSession();
        }
    }

    /**
     * 从 JSON 字符串解析 Cookies
     * 
     * JSON 格式（与 Playwright Cookie 兼容）:
     * [
     *   {"name": "xxx", "value": "yyy", "domain": "zzz", "path": "/", ...},
     *   ...
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
            String name = node.path("name").asText(null);
            String value = node.path("value").asText(null);
            String domain = node.path("domain").asText(null);
            String path = node.path("path").asText("/");
            
            // 如果 domain 为空，尝试从其他字段推断
            if (domain == null || domain.isEmpty()) {
                // 尝试从 url 字段提取
                String url = node.path("url").asText(null);
                if (url != null && !url.isEmpty()) {
                    try {
                        domain = new java.net.URI(url).getHost();
                        log.debug("从 url 字段推断 domain: {} -> {}", url, domain);
                    } catch (Exception ignored) {}
                }
            }
            
            // 如果还是没有 domain，设置默认值
            if (domain == null || domain.isEmpty()) {
                domain = "webvpn.sztu.edu.cn";  // 默认根域名
                log.debug("Cookie {} 无 domain，使用默认值: {}", name, domain);
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

    /**
     * 保存 SmartSession 的 Cookies 到缓存
     */
    private void saveSessionCookies(String wxId, SmartSession smartSession) {
        List<SmartCookie> smartCookies = smartSession.getCookies();
        log.info("💾 saveSessionCookies: 准备保存 {} 个 SmartCookie", smartCookies.size());
        
        // 调试：打印每个 SmartCookie
        for (SmartCookie sc : smartCookies) {
            log.debug("  💾 SmartCookie: name={}, domain={}, value={}...", 
                    sc.getName(), sc.getDomain(), 
                    sc.getValue().length() > 10 ? sc.getValue().substring(0, 10) : sc.getValue());
        }
        
        List<com.microsoft.playwright.options.Cookie> playwrightCookies =
                convertToPlaywrightCookies(smartCookies);
        
        log.info("💾 转换为 {} 个 Playwright Cookie", playwrightCookies.size());
        
        // 调试：打印每个 Playwright Cookie
        for (com.microsoft.playwright.options.Cookie pc : playwrightCookies) {
            log.debug("  💾 Playwright Cookie: name={}, domain={}", pc.name, pc.domain);
        }
        
        authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, playwrightCookies);
        log.info("💾 已调用 saveOrUpdateSessionCookie");
    }

    /**
     * 将 SmartCookie 转换为 Playwright Cookie
     */
    private List<com.microsoft.playwright.options.Cookie> convertToPlaywrightCookies(List<SmartCookie> smartCookies) {
        return smartCookies.stream()
                .map(SmartCookie::toPlaywright)
                .collect(Collectors.toList());
    }
}
