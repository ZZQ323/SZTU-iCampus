package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.application.vo.LoginStatusVo;
import cn.edu.sztui.base.domain.event.UserLoginEvent;
import cn.edu.sztui.base.domain.model.login.LoginType;
import cn.edu.sztui.base.infrastructure.persistence.convertor.CharacterConverter;
import cn.edu.sztui.base.infrastructure.persistence.parser.URLPraser;
import cn.edu.sztui.base.infrastructure.persistence.parser.UserInfoPraser;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import cn.edu.sztui.common.util.smarthttp.dto.SmartCookie;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartSession;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

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

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 状态查询 ====================

    /**
     * 获取登录状态
     * <p>
     * 使用前端 header 传来的 cookies 检测学校登录状态。
     */
    @Override
    public LoginStatusVo getStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        String userId = tokenMessage.getUserId();
        log.debug("用户 {} 查询登录状态", userId);

        // 使用前端传来的 cookies 构建 ProxySession
        ProxySession session = buildSessionFromContext(tokenMessage);

        LoginResultsVo result = doRefreshCookies(userId, session);
        result.setUserId(userId);

        return LoginStatusVo.from(result);
    }

    @Override
    public boolean getSessionStatus() {
        TokenMessage tokenMessage = UserContext.getContext();
        // 如果前端带了 cookies，那 session 就存在
        return tokenMessage != null && tokenMessage.getSchoolCookiesJson() != null;
    }

    @Override
    public List<String> getPossibleUsrId() {
        TokenMessage tokenMessage = UserContext.getContext();
        ProxySession session = authSessionCacheUtil.getSession(tokenMessage.getUserId());
        if (Objects.isNull(session)) {
            return Collections.emptyList();
        }
        return session.getUserIds();
    }

    // ==================== 会话管理 ====================

    /**
     * 初始化会话（公开接口，无需认证）
     * <p>
     * 访问学校 gateway，获取预登录 cookies + loginTypes。
     * 返回明文 cookies 给前端。
     */
    @Override
    public LoginResultsVo initSession() {
        log.info("初始化会话（公开）");

        // 新建空会话，执行刷新
        LoginResultsVo result = doRefreshCookies(null, null);

        return result;
    }

    /**
     * 刷新会话
     * <p>
     * 使用前端传来的 cookies 重新访问学校 gateway，获取最新状态。
     */
    @Override
    public LoginResultsVo refreshSession() {
        TokenMessage tokenMessage = UserContext.getContext();
        String userId = tokenMessage.getUserId();
        log.info("用户 {} 刷新会话", userId);

        ProxySession session = buildSessionFromContext(tokenMessage);

        LoginResultsVo result = doRefreshCookies(userId, session);
        result.setUserId(userId);

        if (!result.isLogined()) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话已过期，请重新登录",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        return result;
    }

    @Override
    @Deprecated
    public LoginResultsVo refresh() {
        return initSession();
    }

    // ==================== 登录/登出（完全不变） ====================

    @Override
    public String getSms(String usrId) {
        log.info("📱 获取短信验证码: userId={}", usrId);

        // 从 UserContext 获取前端通过 header 传来的 cookies（如果有）
        TokenMessage ctx = UserContext.getContext();
        String existingCookies = (ctx != null) ? ctx.getSchoolCookiesJson() : null;

        ProxySession tempSession = new ProxySession();
        if (existingCookies != null && !existingCookies.isEmpty() && !existingCookies.equals("[]")) {
            tempSession.setCookiesJson(existingCookies);
            log.info("🍪 getSms 使用前端传来的 cookies");
        }

        try (SmartSession smartSession = createSmartSession(tempSession)) {

            log.info("🍪 getSms 开始时有 {} 个 Cookie", smartSession.getCookies().size());

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

            log.info("📱 短信请求完成，有 {} 个 Cookie", smartSession.getCookies().size());

            return JSON.toJSONString(smartSession.getCookies());

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
        String userId = cmd.getUserId();

        LoginResultsVo ret = new LoginResultsVo();

        // 从 UserContext 获取 cookies（CookieAuthFilter 从 header 解析）
        TokenMessage ctx = UserContext.getContext();
        String cookiesJson = (ctx != null) ? ctx.getSchoolCookiesJson() : null;

        // fallback: 从请求体读取（兼容旧版前端）
        if (cookiesJson == null || cookiesJson.isEmpty() || cookiesJson.equals("[]")) {
            cookiesJson = cmd.getCookiesJson();
        }

        if (cookiesJson == null || cookiesJson.isEmpty() || cookiesJson.equals("[]")) {
            log.error("❌ 前端未提供 cookies（header 和 body 均为空）");
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "缺少预登录 cookies，请先初始化会话",
                    ResultCodeEnum.BAD_REQUEST.getCode()
            );
        }

        log.info("📦 前端传来 cookiesJson 长度: {}", cookiesJson.length());

        ProxySession tempSession = new ProxySession();
        tempSession.setCookiesJson(cookiesJson);

        try (SmartSession smartSession = createSmartSession(tempSession)) {

            List<SmartCookie> loadedCookies = smartSession.getCookies();
            log.info("🍪 SmartSession 中加载了 {} 个 Cookie", loadedCookies.size());

            for (SmartCookie c : loadedCookies) {
                log.debug("  🍪 {}={} (domain={}, path={})",
                        c.getName(),
                        c.getValue().length() > 20 ? c.getValue().substring(0, 20) + "..." : c.getValue(),
                        c.getDomain(),
                        c.getPath());
            }

            // 发送 AJAX 验证请求
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
                        "登录会话已失效，请回到主页面，并更新会话",
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
            formHeaders.put("Origin", URLPraser.extractOrigin(formSubmitUrl));

            log.info("📤 提交登录表单: url={}", formSubmitUrl);

            SmartResponse formRes = smartHttpClient.post(formSubmitUrl, loginFormData, smartSession);
            log.info("📥 表单提交后最终 URL: {}", formRes.getFinalUrl());

            // ============ 第三步：访问最终页面获取用户信息 ============
            SmartResponse finalRes = smartHttpClient.get(internalNetStartURL, smartSession);

            if (finalRes.getFinalUrl().contains(internalNetStartURL)
                    || finalRes.getFinalUrl().contains("/bmportal/index.portal")) {
                UserInfoPraser.extractByRegex(ret, finalRes.getBody());
                log.info("✅ 从最终页面解析到用户信息: userId={}, realName={}",
                        ret.getUserId(), ret.getRealName());
            }

            // 也尝试从表单响应中解析
            UserInfoPraser.extractByRegex(ret, formRes.getBody());
            log.info("解析到用户信息: userId={}, realName={}", ret.getUserId(), ret.getRealName());

            // ============ 第四步：保存 Cookies 到 Redis（供爬虫引擎使用） ============
            authSessionCacheUtil.sessionLoginBind(userId, userId, smartSession.getCookies());

            // ============ 第五步：返回 cookies 给前端 ============
            ret.setCookiesJson(JSON.toJSONString(smartSession.getCookies()));

            // ============ 第六步：发布登录成功事件 ============
            eventPublisher.publishEvent(new UserLoginEvent(
                    this, userId,
                    ret.getRealName()
            ));

            log.info("✅ 登录成功: userId={}", userId);

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
        String userId = tokenMessage.getUserId();

        try (SmartSession smartSession = createSmartSession(authSessionCacheUtil.getSession(userId))) {

            // 访问登出 URL
            smartHttpClient.get(logoutSubmitURL, smartSession);

            // ⭐ 只调用 sessionLogoutBind，不再调用 invalidateStatusCache
            authSessionCacheUtil.sessionLogoutBind(userId);

            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            return ret;

        } catch (Exception e) {
            log.error("登出失败: {}", e.getMessage());
            // 即使失败也清除本地会话
            authSessionCacheUtil.sessionLogoutBind(userId);

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


    // ==================== 辅助：会话构建 ====================

    /**
     * 从 UserContext 中的 schoolCookiesJson 构建 ProxySession
     */
    private ProxySession buildSessionFromContext(TokenMessage tokenMessage) {
        if (tokenMessage == null || tokenMessage.getSchoolCookiesJson() == null) {
            return null;
        }
        ProxySession session = new ProxySession();
        session.setUserId(tokenMessage.getUserId());
        session.setCookiesJson(tokenMessage.getSchoolCookiesJson());
        return session;
    }

    // ==================== 核心：Cookie 刷新逻辑 ====================

    private LoginResultsVo doRefreshCookies(String userId, ProxySession session) {
        LoginResultsVo ret = new LoginResultsVo();
        ret.setLogined(false);

        log.info("🔄 doRefreshCookies: userId={}, hasSession={}", userId, session != null);

        try (SmartSession smartSession = createSmartSession(session)) {

            log.info("🍪 doRefreshCookies 开始时有 {} 个 Cookie", smartSession.getCookies().size());

            // 访问网关起始页，自动跟随所有重定向
            SmartResponse response = smartHttpClient.get(gatewayStartURL, smartSession);

            String finalUrl = response.getFinalUrl();
            String body = response.getBody();
            log.info("最终 URL: {}, 重定向次数: {}", finalUrl, response.getRedirectCount());

            // 打印重定向链，用于调试
            if (log.isDebugEnabled()) {
                log.debug("重定向链: {}", response.getRedirectChain());
            }

            log.info("🍪 doRefreshCookies 请求后有 {} 个 Cookie", smartSession.getCookies().size());

            // ⭐ 首先检测是否是错误页面（必须在其他判断之前）
            if (isErrorPage(body)) {
                log.warn("⚠️ 检测到错误页面，清空 Cookie 并要求重新登录");

                // 清空该用户的所有缓存
                if (userId != null) {
                    authSessionCacheUtil.deleteSession(userId);
                }

                // 设置需要重新登录
                ret.setLogined(false);
                // ret.setLoginTypes(Arrays.asList(LoginType.SMS, LoginType.PASSWORD));
                ret.setSessionInvalid(true);  // ⭐ 标记会话无效

                log.info("错误页面处理完成: logined=false, sessionInvalid=true");
                return ret;
            }

            // 根据最终 URL 判断登录状态
            if (finalUrl.contains(internalNetStartURL) || finalUrl.contains("/bmportal/index.portal")) {
                // 已登录
                ret.setLogined(true);

                // 解析用户信息
                UserInfoPraser.extractByRegex(ret, body);

                // 发布登录成功事件
                eventPublisher.publishEvent(new UserLoginEvent(
                        this, userId,
                        ret.getRealName()
                ));

            } else if (finalUrl.contains(gatewayFirstURL) || finalUrl.contains("/idp/authcenter/ActionAuthChain")) {
                // 未登录，检测支持的登录方式
                List<LoginType> detectedTypes = detectLoginTypesFromBody(body);
                ret.setLoginTypes(detectedTypes);
                log.info("检测到登录方式（gatewayFirst）: {}", detectedTypes);

            } else if (finalUrl.contains(gatewaySecondURL)) {
                // 未登录，检测支持的登录方式
                List<LoginType> detectedTypes = detectLoginTypesFromBody(body);
                ret.setLoginTypes(detectedTypes);
                log.info("检测到登录方式（gatewaySecond）: {}", detectedTypes);

            } else {
                // 未知状态，尝试从响应体判断
                if (body != null) {
                    // ⭐ 检查是否是真正的登录表单页面（而不是错误页面）
                    if (isRealLoginPage(body)) {
                        // 包含登录表单，未登录状态 - 检测支持的登录方式
                        List<LoginType> detectedTypes = detectLoginTypesFromBody(body);
                        ret.setLoginTypes(detectedTypes);
                        log.info("根据页面内容判断为未登录状态，检测到登录方式: {}", detectedTypes);
                    } else {
                        // 未知页面，但不是登录页
                        log.warn("未知的页面内容，设置默认登录方式");
                        ret.setLoginTypes(Arrays.asList(LoginType.SMS));
                    }
                }

                log.warn("未知的最终页面 URL: {}", finalUrl);
            }

            // 返回 cookies 给前端
            ret.setCookiesJson(JSON.toJSONString(smartSession.getCookies()));

            // 保存到 Redis（供爬虫引擎使用，仅当有 userId 且非错误页面时）
            if (userId != null && !ret.isSessionInvalid()) {
                saveSessionCookies(userId, smartSession);
            }

            log.info("解析到用户信息: userId={}, realName={}, logined={}",
                    ret.getUserId(), ret.getRealName(), ret.isLogined());

            return ret;

        } catch (Exception e) {
            log.error("刷新 Cookie 失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "刷新 Cookie 失败: " + e.getMessage(),
                    ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
            );
        }
    }

    // ==================== ⭐ 新增：错误页面检测 ====================

    /**
     * 检测是否是错误页面
     *
     * 错误页面特征：
     * - 标题包含 "错误信息"
     * - 包含 "当前界面遇到了一些问题"
     * - 包含 "请关闭浏览器页面重试"
     * - 包含 "请清理浏览器缓存"
     * - 包含 "请升级浏览器版本"
     * - 包含 "联系运维人员"
     *
     * @param body 页面 HTML
     * @return true 表示是错误页面
     */
    private boolean isErrorPage(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }

        // 检测错误页面的关键特征
        boolean hasErrorTitle = body.contains("<title>错误信息</title>");
        boolean hasErrorMessage = body.contains("当前界面遇到了一些问题");
        boolean hasRetryHint = body.contains("请关闭浏览器页面重试")
                || body.contains("请清理浏览器缓存")
                || body.contains("请升级浏览器版本");
        boolean hasContactHint = body.contains("联系运维人员");

        // 任意两个特征匹配即认为是错误页面
        int matchCount = 0;
        if (hasErrorTitle) matchCount++;
        if (hasErrorMessage) matchCount++;
        if (hasRetryHint) matchCount++;
        if (hasContactHint) matchCount++;

        if (matchCount >= 2) {
            log.info("检测到错误页面特征: title={}, message={}, retry={}, contact={}",
                    hasErrorTitle, hasErrorMessage, hasRetryHint, hasContactHint);
            return true;
        }

        return false;
    }

    /**
     * 检测是否是真正的登录页面（而不是错误页面）
     *
     * 真正的登录页面需要同时满足：
     * 1. 包含登录表单元素（j_username, j_password 等）
     * 2. 不是错误页面
     *
     * @param body 页面 HTML
     * @return true 表示是真正的登录页面
     */
    private boolean isRealLoginPage(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }

        // 首先排除错误页面
        if (isErrorPage(body)) {
            log.info("页面是错误页面，不是登录页面");
            return false;
        }

        // 检查是否包含登录表单的关键元素
        boolean hasUsernameField = body.contains("j_username") || body.contains("name=\"username\"");
        boolean hasPasswordField = body.contains("j_password") || body.contains("type=\"password\"");
        boolean hasLoginForm = body.contains("action=\"") &&
                (body.contains("login") || body.contains("BAMUsername"));
        boolean hasSmsField = body.contains("sms_checkcode") || body.contains("验证码");

        // 必须有用户名输入框，且至少有密码框或短信验证码
        boolean isLoginPage = hasUsernameField && (hasPasswordField || hasSmsField || hasLoginForm);

        log.debug("登录页面检测: username={}, password={}, form={}, sms={}, result={}",
                hasUsernameField, hasPasswordField, hasLoginForm, hasSmsField, isLoginPage);

        return isLoginPage;
    }

    // ==================== 登录方式检测 ====================

    /**
     * 从登录页面 HTML 中检测支持的登录方式
     *
     * 检测策略（优先级从高到低）：
     * 1. Tab 按钮：tabA1/tab1 = 密码，tabA4/tab4 = SMS
     * 2. 输入框：j_password = 密码，sms_checkcode = SMS
     * 3. 表单 action：BAMUsernamePassword = 密码，BAMUsernameOTP = SMS
     * 4. 默认至少支持 SMS
     *
     * @param body 登录页面 HTML
     * @return 支持的登录方式列表
     */
    private List<LoginType> detectLoginTypesFromBody(String body) {
        List<LoginType> types = new ArrayList<>();

        if (body == null || body.isEmpty()) {
            types.add(LoginType.SMS);
            return types;
        }

        // ⭐ 再次检查是否是错误页面（双重保险）
        if (isErrorPage(body)) {
            log.warn("detectLoginTypesFromBody: 检测到错误页面，返回默认登录方式");
            types.add(LoginType.SMS);
            types.add(LoginType.PASSWORD);
            return types;
        }

        // ========== 1. 优先检测 Tab 按钮（最可靠） ==========
        // 页面结构：
        // <a id="tabA1" ...><li><span class="tab tab1" title="用户名密码认证"></span></li></a>
        // <a id="tabA4" ...><li><span class="tab tab4" title="用户名短信认证"></span></li></a>

        boolean hasPasswordTab = body.contains("tabA1") || body.contains("用户名密码认证");
        boolean hasSmsTab = body.contains("tabA4") || body.contains("用户名短信认证");

        // ⭐ 注意：tab1 可能在错误页面中也存在，所以需要更严格的检查
        // 只有同时包含 tab1 和登录表单元素才认为支持密码登录
        if (!hasPasswordTab && body.contains("tab1")) {
            // 额外检查是否真的有密码输入框
            if (body.contains("j_password") || body.contains("type=\"password\"")) {
                hasPasswordTab = true;
                log.info("检测到 tab1 + 密码输入框 - 支持密码登录");
            }
        }

        if (hasPasswordTab) {
            log.info("检测到 tabA1/用户名密码认证 - 支持密码登录");
        }
        if (hasSmsTab) {
            log.info("检测到 tabA4/用户名短信认证 - 支持短信登录");
        }

        // ========== 2. 补充检测：密码登录 ==========
        boolean hasPasswordLogin = hasPasswordTab;

        if (!hasPasswordLogin) {
            // 方式一：检查 j_password 输入框是否存在且可见
            if (body.contains("j_password") && body.contains("type=\"password\"")) {
                int passwordIndex = body.indexOf("j_password");
                if (passwordIndex > 0) {
                    int start = Math.max(0, passwordIndex - 200);
                    int end = Math.min(body.length(), passwordIndex + 200);
                    String context = body.substring(start, end);
                    if (!context.contains("display:none") && !context.contains("display: none")) {
                        hasPasswordLogin = true;
                        log.info("检测到 j_password 输入框可见");
                    }
                }
            }

            // 方式二：检查 passwordLayer 元素
            if (body.contains("passwordLayer")) {
                if ((body.contains(".passwordLayer") && body.contains("display:block"))
                        || (body.contains(".passwordLayer") && body.contains("display: block"))) {
                    hasPasswordLogin = true;
                    log.info("检测到 passwordLayer CSS display:block");
                }
                if (body.contains("entityId=home") || body.contains("gatewaySecond")
                        || body.contains("BAMUsernamePassword")) {
                    if (!body.contains("passwordLayer") || !body.contains("style=\"display:none\"")) {
                        hasPasswordLogin = true;
                        log.info("检测到第二关页面，支持密码登录");
                    }
                }
            }

            // 方式三：直接检查表单 action URL
            if (body.contains("BAMUsernamePassword")) {
                hasPasswordLogin = true;
                log.info("检测到 BAMUsernamePassword 表单");
            }
        }

        // ========== 3. 补充检测：SMS 登录 ==========
        boolean hasSmsLogin = hasSmsTab;

        if (!hasSmsLogin) {
            if (body.contains("sms_checkcode")
                    || body.contains("短信验证")
                    || body.contains("验证码登录")
                    || body.contains("BAMUsernameOTP")
                    || body.contains("entityId=webvpn")) {
                hasSmsLogin = true;
            }

            // ⭐ 如果有用户名输入框但没检测到其他登录方式，默认支持 SMS
            if (!hasSmsLogin && body.contains("j_username")) {
                hasSmsLogin = true;
                log.info("检测到 j_username，默认支持短信登录");
            }
        }

        // ========== 4. 添加检测到的登录方式 ==========
        // SMS 优先
        if (hasSmsLogin) {
            types.add(LoginType.SMS);
        }
        if (hasPasswordLogin) {
            types.add(LoginType.PASSWORD);
        }

        // 确保至少有一种登录方式
        if (types.isEmpty()) {
            types.add(LoginType.SMS);
        }

        log.info("检测登录方式 - body长度: {}, hasPasswordTab: {}, hasSmsTab: {}, 结果: {}",
                body.length(), hasPasswordTab, hasSmsTab, types);

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
     *
     * ⭐ 直接使用 SmartCookie JSON 序列化，不再依赖 Playwright
     */
    private void saveSessionCookies(String userId, SmartSession smartSession) {
        List<SmartCookie> smartCookies = smartSession.getCookies();
        log.info("💾 saveSessionCookies: 准备保存 {} 个 SmartCookie", smartCookies.size());

        // 调试：打印每个 SmartCookie
        for (SmartCookie sc : smartCookies) {
            log.debug("  💾 SmartCookie: name={}, domain={}, value={}...",
                    sc.getName(), sc.getDomain(),
                    sc.getValue().length() > 10 ? sc.getValue().substring(0, 10) : sc.getValue());
        }

        // ⭐ 直接保存 SmartCookie，不再转换为 Playwright Cookie
        authSessionCacheUtil.saveOrUpdateSessionCookie(userId, smartCookies);
        log.info("💾 已调用 saveOrUpdateSessionCookie");
    }
}