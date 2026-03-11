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
 * 认证服务 V2 实现（基于 SmartHttpClient，无浏览器）
 *
 * 【精简版 - 只修改 Redis 缓存调用】：
 * - 删除状态缓存逻辑（getCachedStatus/cacheStatus/invalidateStatusCache）
 * - 删除 Cookie 过期预测逻辑（isCookiePossiblyExpired/isCookieExpiringSoon/clearSessionCookies）
 * - 登录流程完全不变
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
     * 获取登录状态（精简版 - 不做缓存）
     */
    @Override
    public LoginStatusVo getStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.debug("用户 {} 查询登录状态", wxId);

        // 直接获取真实状态（不做缓存）
        LoginResultsVo result = doRefreshCookies(wxId, authSessionCacheUtil.getSession(wxId));

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
     * 初始化会话（精简版）
     */
    @Override
    public LoginResultsVo initSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        log.info("用户 {} 初始化会话（SmartHttpClient V2）", wxId);

        // ⭐ 强制删除旧 ProxySession（替代原来的 invalidateStatusCache + clearSessionCookies）
        authSessionCacheUtil.deleteSession(wxId);

        // 执行刷新
        return doRefreshCookies(wxId, null);
    }

    /**
     * 刷新会话（精简版）
     */
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
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话已过期，请重新登录",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        return result;
    }

    @Override
    @Deprecated
    public LoginResultsVo refresh() {
        return initSession();
    }

    // ==================== 核心：Cookie 刷新逻辑（完全不变） ====================

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
            if (finalUrl.contains(internalNetStartURL) || finalUrl.contains("/bmportal/index.portal")) {
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

            } else if (finalUrl.contains(gatewayFirstURL) || finalUrl.contains("/idp/authcenter/ActionAuthChain")) {
                // 未登录，检测支持的登录方式
                List<LoginType> detectedTypes = detectLoginTypesFromBody(response.getBody());
                ret.setLoginTypes(detectedTypes);
                log.info("检测到登录方式（gatewayFirst）: {}", detectedTypes);

            } else if (finalUrl.contains(gatewaySecondURL)) {
                // 未登录，检测支持的登录方式
                List<LoginType> detectedTypes = detectLoginTypesFromBody(response.getBody());
                ret.setLoginTypes(detectedTypes);
                log.info("检测到登录方式（gatewaySecond）: {}", detectedTypes);

            } else {
                // 未知状态，尝试从响应体判断
                String body = response.getBody();
                if (body != null) {
                    if (body.contains("j_username") || body.contains("登录") || body.contains("login")) {
                        // 包含登录表单，未登录状态 - 检测支持的登录方式
                        List<LoginType> detectedTypes = detectLoginTypesFromBody(body);
                        ret.setLoginTypes(detectedTypes);
                        log.info("根据页面内容判断为未登录状态，检测到登录方式: {}", detectedTypes);
                    } else if (body.contains("bmportal") || body.contains("userInfo") || body.contains("个人中心")) {
                        // 包含门户内容，已登录状态
                        ret.setLogined(true);
                        UserInfoPraser.extractByRegex(ret, body);
                        log.info("根据页面内容判断为已登录状态");
                    } else {
                        // 默认为未登录，只支持 SMS
                        ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                        log.info("无法根据页面内容判断，默认SMS");
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

    // ==================== 登录/登出（完全不变） ====================

    @Override
    public void getSms(String usrId) {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        log.info("📱 获取短信验证码: openId={}, userId={}", wxId, usrId);

        // ⭐ 关键修复：从 WebVPN 入口开始，建立完整的会话链路
        // 而不是直接访问认证系统
        try (SmartSession smartSession = smartHttpClient.newSession()) {

            log.info("🍪 getSms 开始时有 0 个 Cookie（新会话）");

            // ⭐ 第一步：访问 WebVPN 入口，建立完整的会话链路
            // 使用 thdportal_login URL 跳过 /por/ 页面的 JS 重定向
            String redirectUri = "https://home-sztu-edu-cn-s.webvpn.sztu.edu.cn:8118/bmportal";
            String thdLoginUrl = "https://webvpn.sztu.edu.cn/public/thdportal_login?redirect_uri=" +
                    java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8);

            log.info("📍 第一步：访问 WebVPN 入口建立会话链路...");
            log.debug("📍 URL: {}", thdLoginUrl);

            SmartResponse loginPageRes = smartHttpClient.get(thdLoginUrl, smartSession);

            log.info("📍 登录页面最终 URL: {}", loginPageRes.getFinalUrl());
            log.info("🍪 收集到 {} 个 Cookie", smartSession.getCookies().size());

            for (SmartCookie c : smartSession.getCookies()) {
                log.debug("  🍪 {}={} (domain={})",
                        c.getName(),
                        c.getValue().length() > 10 ? c.getValue().substring(0, 10) + "..." : c.getValue(),
                        c.getDomain());
            }

            // ⭐ 第二步：发送短信验证码请求
            log.info("📍 第二步：发送短信验证码请求...");

            Map<String, String> formData = new HashMap<>();
            formData.put("j_username", CharacterConverter.toSBC(usrId));

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", loginPageRes.getFinalUrl());
            headers.put("Origin", URLPraser.extractOrigin(gatewaySmsReqURL));

            SmartResponse response = smartHttpClient.postAjax(
                    gatewaySmsReqURL + "?sf_request_type=ajax",
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
                        "获取短信验证码失败，请重试",
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

        LoginResultsVo ret = new LoginResultsVo();

        // 检查 ProxySession
        ProxySession cachedSession = authSessionCacheUtil.getSession(wxId);
        if (cachedSession == null || cachedSession.getCookiesJson() == null
                || cachedSession.getCookiesJson().isEmpty()
                || cachedSession.getCookiesJson().equals("[]")) {
            log.error("❌ ProxySession 为空或无 Cookie! openId={}", wxId);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话不存在，请先获取短信验证码",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        log.info("📦 ProxySession.cookiesJson 长度: {}", cachedSession.getCookiesJson().length());

        try (SmartSession smartSession = createSmartSession(cachedSession)) {

            List<SmartCookie> loadedCookies = smartSession.getCookies();
            log.info("🍪 SmartSession 中加载了 {} 个 Cookie", loadedCookies.size());

            for (SmartCookie c : loadedCookies) {
                log.debug("  🍪 {}={} (domain={}, path={})",
                        c.getName(),
                        c.getValue().length() > 20 ? c.getValue().substring(0, 20) + "..." : c.getValue(),
                        c.getDomain(),
                        c.getPath());
            }

            // ⭐ 关键修复：不再访问登录页面，直接发送 AJAX 验证请求
            // 因为再次访问登录页面可能会重置服务器端的验证码状态
            String refererUrl = (cmd.getLoginType() == LoginType.SMS) ? gatewayFirstURL : gatewaySecondURL;

            // ============ 第一步：AJAX 验证 ============
            Map<String, String> verifyFormData = buildAjaxVerifyFormData(cmd);
            Map<String, String> verifyHeaders = new HashMap<>();

            verifyHeaders.put("Referer", refererUrl);
            verifyHeaders.put("Origin", URLPraser.extractOrigin(gatewayLoginSubmitURL));
            verifyHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            verifyHeaders.put("Accept", "*/*");
            verifyHeaders.put("X-Requested-With", "XMLHttpRequest");

            log.info("📤 发送 AJAX 验证请求: url={}", gatewayLoginSubmitURL + "?sf_request_type=ajax");
            log.debug("📤 表单数据: {}", verifyFormData);

            SmartResponse ajaxRes = smartHttpClient.postAjax(
                    gatewayLoginSubmitURL + "?sf_request_type=ajax",
                    verifyFormData,
                    smartSession,
                    verifyHeaders
            );

            String ajaxBody = ajaxRes.getBody();
            log.info("📥 AJAX 响应: status={}, bodyLength={}",
                    ajaxRes.getStatusCode(), ajaxBody != null ? ajaxBody.length() : 0);

            if (ajaxBody != null) {
                if (ajaxBody.length() < 500) {
                    log.debug("📥 响应内容: {}", ajaxBody);
                } else {
                    log.debug("📥 响应前500字符: {}", ajaxBody.substring(0, 500));
                }
            }

            // 检查响应是否为 HTML（被重定向或会话错误）
            if (ajaxBody == null || ajaxBody.trim().startsWith("<")) {
                log.error("❌ AJAX 请求返回了 HTML 而非 JSON");
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
            formHeaders.put("Origin", URLPraser.extractOrigin(gatewayLoginSubmitURL));

            log.info("📤 提交登录表单: url={}", formSubmitUrl);

            SmartResponse formRes = smartHttpClient.post(formSubmitUrl, loginFormData, smartSession);
            log.info("📥 表单提交后最终 URL: {}", formRes.getFinalUrl());

            // ============ 第三步：访问最终页面获取用户信息 ============
            SmartResponse finalRes = smartHttpClient.get(gatewayStartURL, smartSession);

            if (finalRes.getFinalUrl().contains(internalNetStartURL)
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

            // ⭐ 第五步已删除：不再调用 invalidateStatusCache

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
    public LoginResultsVo logout() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(wxId))) {

            // 访问登出 URL
            smartHttpClient.get(logoutSubmitURL, smartSession);

            // ⭐ 只调用 sessionLogoutBind，不再调用 invalidateStatusCache
            authSessionCacheUtil.sessionLogoutBind(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;

        } catch (Exception e) {
            log.error("登出失败: {}", e.getMessage());
            // 即使失败也清除本地会话
            authSessionCacheUtil.sessionLogoutBind(wxId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;
        }
    }

    // ==================== 表单构建方法（完全不变） ====================

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
            return A4tLoginSMSRedirectURL;
        } else {
            return A4tLoginPASSWORDRedirectURL;
        }
    }

    /**
     * 从页面 HTML 检测支持的登录方式
     *
     * 解析逻辑（基于学校登录页面 HTML 结构）：
     * 1. 检查 passwordLayer 是否显示（通过内联样式或 CSS 判断）
     * 2. 检查 j_password 输入框是否存在
     * 3. 检查 SMS 相关元素
     * 4. 默认至少支持 SMS
     *
     * @param body 登录页面 HTML
     * @return 支持的登录方式列表
     */
    private List<LoginType> detectLoginTypesFromBody(String body) {
        List<LoginType> types = new ArrayList<>();

        if (body == null || body.isEmpty()) {
            // 默认返回 SMS
            types.add(LoginType.SMS);
            return types;
        }

        // ========== 1. 检测是否支持密码登录 ==========
        boolean hasPasswordLogin = false;

        // 方式一：检查 j_password 输入框是否存在且可见
        // 页面结构：<input id="j_password" class="inputLogin" type="password" name="j_password" ...>
        if (body.contains("j_password") && body.contains("type=\"password\"")) {
            // 检查是否被隐藏（通过内联样式）
            // 查找 j_password 相关的元素是否有 display:none
            int passwordIndex = body.indexOf("j_password");
            if (passwordIndex > 0) {
                // 检查前后 200 字符内是否有 display:none
                int start = Math.max(0, passwordIndex - 200);
                int end = Math.min(body.length(), passwordIndex + 200);
                String context = body.substring(start, end);

                // 如果没有 display:none，说明密码登录可见
                if (!context.contains("display:none") && !context.contains("display: none")) {
                    hasPasswordLogin = true;
                    log.debug("检测到 j_password 输入框可见");
                }
            }
        }

        // 方式二：检查 passwordLayer 元素
        // 页面结构：<div class="passwordLayer">密码</div>
        // 如果 passwordLayer 的 CSS 是 display:block，则支持密码登录
        if (body.contains("passwordLayer")) {
            // 检查 <style> 标签中的 CSS
            // 查找 .passwordLayer { display: block; ... }
            if (body.contains(".passwordLayer") && body.contains("display:block")
                    || body.contains(".passwordLayer") && body.contains("display: block")) {
                hasPasswordLogin = true;
                log.debug("检测到 passwordLayer CSS display:block");
            }

            // 检查内联样式：如果 passwordLayer 元素没有 style="display:none"
            // 且页面是第二关（gatewaySecond / entityId=home），默认认为密码登录可用
            if (body.contains("entityId=home") || body.contains("gatewaySecond")
                    || body.contains("BAMUsernamePassword")) {
                // 这是第二关页面，通常支持密码登录
                if (!body.contains("passwordLayer") || !body.contains("style=\"display:none\"")) {
                    hasPasswordLogin = true;
                    log.debug("检测到第二关页面，支持密码登录");
                }
            }
        }

        // 方式三：直接检查表单 action URL
        // A4tLoginPASSWORDFormActionURL = "...BAMUsernamePassword..."
        if (body.contains("BAMUsernamePassword")) {
            hasPasswordLogin = true;
            log.debug("检测到 BAMUsernamePassword 表单");
        }

        // ========== 2. 检测是否支持 SMS 登录 ==========
        boolean hasSmsLogin = false;

        // SMS 登录始终可用（基础登录方式）
        // 检查 SMS 相关元素作为确认
        if (body.contains("sms_checkcode")
                || body.contains("短信验证")
                || body.contains("验证码登录")
                || body.contains("j_username")
                || body.contains("BAMUsernameOTP")
                || body.contains("entityId=webvpn")) {
            hasSmsLogin = true;
        }

        // ========== 3. 添加检测到的登录方式 ==========
        // SMS 作为默认登录方式
        if (hasSmsLogin || !hasPasswordLogin) {
            types.add(LoginType.SMS);
        }

        if (hasPasswordLogin) {
            types.add(LoginType.PASSWORD);
        }

        // 确保至少有一种登录方式
        if (types.isEmpty()) {
            types.add(LoginType.SMS);
        }

        log.info("检测登录方式 - body长度: {}, 结果: {}", body.length(), types);

        return types;
    }

    // ==================== 辅助方法（完全不变） ====================

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