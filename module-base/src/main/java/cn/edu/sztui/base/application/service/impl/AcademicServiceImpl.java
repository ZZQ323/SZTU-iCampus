package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVO;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.browserpool.PlaywrightBrowserPool;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.cache.dto.ProxySession;
import cn.edu.sztui.base.infrastructure.util.praser.CrouseParser;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.enums.ResultCodeEnum;
import cn.edu.sztui.common.util.enums.SysReturnCode;
import cn.edu.sztui.common.util.exception.BusinessException;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static cn.edu.sztui.base.domain.model.SchoolAPIs.*;

//学院信息服务
@Slf4j
@Service
public class AcademicServiceImpl implements AcademicService {

    @Resource
    private PlaywrightBrowserPool browserPool;
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;
    @Resource
    private CrouseParser crouseParser;

    @Override
    public LoginResultsVo init() {
        TokenMessage tokenmesage = UserContext.getContext();
        String wxId = tokenmesage.getOpenId();
        return refreshingCookies(wxId);
    }

    private LoginResultsVo refreshingCookies(String wxId) {
        return browserPool.executeWithContext(context -> {
            ProxySession session = authSessionCacheUtil.getSession(wxId);
            if (Objects.isNull(session))
                throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "登录验证失败:", ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
            context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

            // 第一步：访问教务页面，然后静等更新cookie
            LoginResultsVo ret = new LoginResultsVo();
            try {
                Page page = context.newPage();
                Response response = page.navigate(AASysGatewayURL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.COMMIT));
                page.waitForLoadState(LoadState.NETWORKIDLE);
                // 正是选课期间，可以进行礼貌的提醒
                if (response.url().equals(AASysSwitchPort)) ret.setComents("最近正在选课！");
            } catch (Exception e) {
                log.error("会话初始化出现错误：" + e.getMessage());
                throw new BusinessException(SysReturnCode.BASE_PROXY.getCode(), "会话初始化出现错误：" + e.getMessage(),
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode());
            }
            // ============ 第二步：更新网关的cookie  ============
            log.info("用户 {}，获得cookies{}", wxId, context.cookies());
            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());
            return ret;
        });
    }

    @Override
    public CourseTableVO getCrouseTable(CrouseTableQuery query) {
        return browserPool.executeWithContext(context -> {
            TokenMessage tokenmesage = UserContext.getContext();
            String wxId = tokenmesage.getOpenId();
            if (authSessionCacheUtil.hasSession(wxId)) {
                ProxySession session = authSessionCacheUtil.getSession(wxId);
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }
            // 访问登录页面
            APIRequestContext req = context.request();
            APIResponse res;
            if( Objects.isNull(query.getWeek()) && Objects.isNull(query.getSemester())){
                res = req.get(scheduleTableURL + "?sf_request_type=ajax",
                    RequestOptions.create()
                        .setHeader("X-Requested-With", "XMLHttpRequest")
                        .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                        .setHeader("Referer", gatewayFirstEndURL)
                        .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"));
            }else {
                // 重要！必须是表单形式
                FormData formData = FormData.create();
                formData.set("zc", query.getWeek());                            // 第几周
                formData.set("xnxq01id", query.getSemester());                  // 学年学期
                formData.set("cj0701id", "");                                   // 无意义
                formData.set("demo", "");                                       // 无意义
                formData.set("sfFD", "1");                                      // 是否放大
                formData.set("wkbkc", "1");                                     // 显示无课表课程
                formData.set("kbjcmsid", "EB5693B95B204102B2E28C5624C6E9ED");   // 时间模式
                res = req.post(scheduleTableURL + "?sf_request_type=ajax",
                    RequestOptions.create()
                        .setForm(formData)
                        .setHeader("X-Requested-With", "XMLHttpRequest")
                        .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                        .setHeader("Referer", gatewayFirstEndURL)
                        .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0")
                );
            }
            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());
            return crouseParser.parseCourseTable(res.text());
        });
    }
}
