package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.command.LoginRequestCommand;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.service.AuthService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.domain.model.loginhandle.HandleCluster;
import cn.edu.sztui.base.domain.model.loginhandle.LoginHandle;
import cn.edu.sztui.base.domain.model.loginhandle.LoginType;
import cn.edu.sztui.base.infrastructure.convertor.CharacterConverter;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.browserpool.PlaywrightBrowserPool;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.dto.ProxySession;
import cn.edu.sztui.base.infrastructure.util.ocr.CaptchaOcrUtil;
import cn.edu.sztui.base.infrastructure.util.praser.HtmlPraser;
import cn.edu.sztui.base.infrastructure.util.praser.URLPraser;
import cn.edu.sztui.base.infrastructure.wx.WxMaUserService;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cn.edu.sztui.base.domain.model.SchoolAPIs.*;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private PlaywrightBrowserPool browserPool;
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;
    @Resource
    private AcademicService academicService;
    @Resource
    private CaptchaOcrUtil captchaOcrUtil;
    @Resource
    private WxMaUserService wxMaUserService;
    @Autowired
    private ObjectMapper  objectMapper;
    @Autowired
    private HandleCluster handleCluster;

    /**
     * 检测小程序账号的cookie活性
     */
    @Override
    public boolean getSessionStatus() {
        TokenMessage tokenmesage = UserContext.getContext();
        String wxId = tokenmesage.getOpenId();
        return authSessionCacheUtil.hasSession(wxId);
    }

    @Override
    public List<String> getPossibleUsrId() {
        TokenMessage tokenmesage = UserContext.getContext();
        ProxySession session = authSessionCacheUtil.getSession(tokenmesage.getOpenId());
        if(Objects.isNull(session)){
            return Collections.emptyList();
        }
        return session.getUserIds();
    }

    @Override
    public LoginResultsVo init() {
        TokenMessage tokenmesage = UserContext.getContext();
        log.info("用户{} 进行初始化",tokenmesage);
        String wxId = tokenmesage.getOpenId();
        return refreshingCookies(wxId, authSessionCacheUtil.getSession(wxId));
    }

    private LoginResultsVo refreshingCookies(String wxId, ProxySession session) {
        return browserPool.executeWithContext(context -> {
            boolean isUpdated = false;
            if (!Objects.isNull(session)) {
                context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));
                isUpdated = true;
            }
            // 访问登录页面
            LoginResultsVo ret = new LoginResultsVo();
            ret.setLogined(false);
            try {
                Page page = context.newPage();
                // page.navigate(gatewayStartURL);
                // page.waitForURL(gatewayEndURL);
                Response response = page.waitForResponse(
                        resp -> resp.url().equals(gatewayFirstEndURL)
                                || resp.url().equals(gatewaySecondEndURL)
                                || resp.url().equals(acdemAdminSysGatewayStartURL),
                        () -> page.navigate(gatewayStartURL)  // 这是在等待期间执行的动作
                );
                if( response.url().equals(acdemAdminSysGatewayStartURL)){
                    ret.setLogined(true);
                    // log.info("表单结束的文本内容{}", response.text());
                    HtmlPraser.extractByRegex(ret,response.text());
                }else if( response.url().equals(gatewayFirstEndURL) ){
                    ret.setLoginTypes(Collections.singletonList(LoginType.SMS));
                }else if( response.url().equals(gatewaySecondEndURL) ){
                    // Collections.singletonList返回的是一个不可变的列表，只包含一个元素
                    List<LoginType> typeLists = new ArrayList<>();
                    typeLists.add(LoginType.SMS);
                    typeLists.add(LoginType.PASSWORD);
                    ret.setLoginTypes(typeLists);
                }else{
                    throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "未知的页面网络！！！" , ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
                }
            } catch (Exception e) {
                log.error("会话初始化出现错误：" + e.getMessage());
                throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "会话初始化出现错误：" + e.getMessage(), ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
            }
            if (isUpdated) authSessionCacheUtil.saveOrUpdateSession(wxId, context.cookies());
            else authSessionCacheUtil.saveOrUpdateSession(wxId, context.cookies());
            return ret;
        });
    }

    @Override
    public void getSms(String usrId) {
        browserPool.executeWithContext(context -> {
            TokenMessage tokenmesage = UserContext.getContext();
            String wxId = tokenmesage.getOpenId();
            // 缓存检测
            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }
            // 访问登录页面
            APIRequestContext req = context.request();
            // 重要！必须是表单形式
            FormData formData = FormData.create();
            formData.set("j_username", CharacterConverter.toSBC(usrId));
            // formData.put("j_authMethodID", "41");
            APIResponse res = req.post(smsURL+ "?sf_request_type=ajax",
                RequestOptions.create()
                .setForm(formData)
                .setHeader("X-Requested-With", "XMLHttpRequest")
                .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .setHeader("Referer", gatewayFirstEndURL)
                .setHeader("Origin", URLPraser.extractOrigin(smsURL))
                .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
            );
            // log.info("SMS请求URL: {}", smsURL);
            // log.info("SMS请求状态: {}", res.status());
            // log.info("SMS响应头: {}", res.headers());
            // log.info("SMS响应体: {}", res.text());

            // FIXME 如何确保 webvpn的cookie到账？
            authSessionCacheUtil.saveOrUpdateSession(wxId, context.cookies());
            return null;
        });
    }

    public LoginResultsVo loginFrame(LoginRequestCommand cmd) {
        return browserPool.executeWithContext(context -> {
            TokenMessage tokenmesage = UserContext.getContext();
            String wxId = tokenmesage.getOpenId();

            LoginResultsVo ret = new LoginResultsVo();
            if (authSessionCacheUtil.hasSession(wxId)) {
                // 缓存检测
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }

            // ============ 第一步：AJAX 验证 ============
            // 访问登录页面
            LoginHandle loginHandle = handleCluster.getSpringLoginHandle(cmd.getLoginType());
            APIResponse ajaxRes = loginHandle.loginVerification(context, cmd);
            // 获取类重构
            String ajaxBody = ajaxRes.text();
            // log.info("AJAX验证响应: {}", ajaxBody); // 一般是响应 {"loginFailed":"false"}
            // jackson 库中的核心类
            JsonNode json = objectMapper.readTree(ajaxBody);
            boolean loginFailed = json.path("loginFailed").asBoolean(true);

            if (loginFailed)
                throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "登录验证失败: " + ajaxBody, ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());

            // ============ 第二步：模拟表单提交（处理重定向链） ============
            // 获取类重构
            APIResponse formRes = loginHandle.loginRedirect(context, cmd);

            // log.info("表单提交状态: {}", formRes.status());
            log.info("表单提交后 cookies: {}", context.cookies());
            // log.info("表单结束的文本内容{}", formRes.text());

            // ============ 第三步：更新网关的cookie  ============
            authSessionCacheUtil.sessionLoginBind(wxId, cmd.getUserId(), context.cookies());

            // ============ 第四步：获取教务cookie（处理重定向链） ============
            academicService.init();
            log.info("获取到的 教务cookies: {}", context.cookies());

            ret.setWxId(wxId);
            ret.setLogined(true);
            return ret;
        });
    }

    @Override
    public LoginResultsVo logout(LoginRequestCommand cmd) {
        return browserPool.executeWithContext(context -> {
            TokenMessage tokenmesage = UserContext.getContext();
            String wxId = tokenmesage.getOpenId();
            // 缓存检测
            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }
            // 访问登录页面
            APIRequestContext req = context.request();
            APIResponse res = req.get(logoutURL);
//            log.info("退出响应:{} {}", res.status(),res.text());
            
            authSessionCacheUtil.sessionLogoutBind( wxId );
            LoginResultsVo ret = new LoginResultsVo();
            return ret;
        });
    }
}
