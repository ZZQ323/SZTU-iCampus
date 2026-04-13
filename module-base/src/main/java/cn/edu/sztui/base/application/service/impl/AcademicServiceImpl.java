package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.base.infrastructure.convertor.CookieConverter;
import cn.edu.sztui.base.infrastructure.util.cache.AuthSessionCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.CrouseParser;
import cn.edu.sztui.common.cache.dto.ProxySession;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.bean.TokenMessage;
import cn.edu.sztui.common.util.browserpool.PlaywrightBrowserPoolCommonsVersion;
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

/**
 * 教务系统服务实现
 * <p>
 * 更新内容：
 * <ol>
 *   <li>【新增】getCrouseTableByOpenId 方法，支持直接传入 wxOpenId</li>
 *   <li>【重构】抽取 doGetCrouseTable 核心逻辑</li>
 * </ol>
 */
@Slf4j
@Service
public class AcademicServiceImpl implements AcademicService {

    @Resource
    private PlaywrightBrowserPoolCommonsVersion browserPool;
    @Resource
    private AuthSessionCacheUtil authSessionCacheUtil;
    @Resource
    private CrouseParser crouseParser;

    // ==================== 初始化 ====================

    @Override
    public LoginResultsVo init() {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();

        if (!authSessionCacheUtil.isSchoolLoggedIn(wxId)) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "请先登录学校系统",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }
        log.info("用户 {} 初始化教务系统会话", wxId);
        return refreshingCookies(wxId);
    }

    private LoginResultsVo refreshingCookies(String wxId) {
        return browserPool.executeWithContext(context -> {
            ProxySession session = authSessionCacheUtil.getSession(wxId);
            if (Objects.isNull(session) || !session.isSchoolLoggedIn()) {
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "会话不存在或未登录，请先登录学校系统",
                        ResultCodeEnum.UNAUTHORIZED.getCode()
                );
            }

            context.addCookies(CookieConverter.fromCookieDTOs(session.getCookiesJson()));

            LoginResultsVo ret = new LoginResultsVo();
            try {
                Page page = context.newPage();
                Response response = page.navigate(AASysGatewayURL, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.COMMIT));
                page.waitForLoadState(LoadState.NETWORKIDLE);

                if (response.url().equals(AASysSwitchPort)) {
                    ret.setComents("最近正在选课！");
                }
                ret.setLogined(true);
            } catch (com.microsoft.playwright.TimeoutError e) {
                log.error("教务系统访问超时: {}", e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "教务系统响应超时，请稍后重试",
                        ResultCodeEnum.GATEWAY_TIMEOUT.getCode()
                );
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("教务系统初始化出现错误: {}", e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "教务系统初始化失败：" + e.getMessage(),
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            log.info("用户 {} 教务系统 Cookie 已更新", wxId);
            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());
            authSessionCacheUtil.invalidateStatusCache(wxId);

            return ret;
        }, browserPool.getSlowTimeoutSeconds());
    }

    // ==================== 获取课表 ====================

    /**
     * 获取课表（从 UserContext 获取 wxId）
     * <p>
     * 用于 HTTP 请求场景
     */
    @Override
    public CourseTableVo getCrouseTable(CrouseTableQuery query) {
        TokenMessage tokenMessage = UserContext.getContext();
        String wxId = tokenMessage.getOpenId();
        return doGetCrouseTable(wxId, query);
    }

    /**
     * 【新增】获取课表（直接传入 wxOpenId）
     * <p>
     * 用于异步场景（SSE 推送、定时任务）
     */
    @Override
    public CourseTableVo getCrouseTableByOpenId(String wxOpenId, CrouseTableQuery query) {
        return doGetCrouseTable(wxOpenId, query);
    }

    /**
     * 【核心逻辑】获取课表
     *
     * @param wxId  微信 OpenId
     * @param query 查询参数
     * @return 课表数据
     */
    private CourseTableVo doGetCrouseTable(String wxId, CrouseTableQuery query) {
        // 检查是否已登录
        if (!authSessionCacheUtil.isSchoolLoggedIn(wxId)) {
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "请先登录学校系统",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        // 检查 Cookie 是否可能过期
        if (authSessionCacheUtil.isCookiePossiblyExpired(wxId)) {
            log.warn("Cookie 可能已过期: openId={}", wxId);
            throw new BusinessException(
                    SysReturnCode.BASE_PROXY.getCode(),
                    "会话已过期，请重新初始化教务系统",
                    ResultCodeEnum.UNAUTHORIZED.getCode()
            );
        }

        return browserPool.executeWithContext(context -> {
            ProxySession session = authSessionCacheUtil.getSession(wxId);
            if (session != null && session.getCookiesJson() != null) {
                List<Cookie> preCookies = CookieConverter.fromCookieDTOs(session.getCookiesJson());
                context.addCookies(preCookies);
            }

            APIRequestContext req = context.request();
            APIResponse res;

            try {
                if (Objects.isNull(query.getWeek()) && Objects.isNull(query.getSemester())) {
                    // 默认查询（当前周、当前学期）
                    res = req.get(scheduleTableURL + "?sf_request_type=ajax",
                            RequestOptions.create()
                                    .setHeader("X-Requested-With", "XMLHttpRequest")
                                    .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                                    .setHeader("Referer", gatewayFirstEndURL)
                                    .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"));
                } else {
                    // 指定周/学期查询
                    FormData formData = FormData.create();
                    formData.set("zc", query.getWeek());
                    formData.set("xnxq01id", query.getSemester());
                    formData.set("cj0701id", "");
                    formData.set("demo", "");
                    formData.set("sfFD", "1");
                    formData.set("wkbkc", "1");
                    formData.set("kbjcmsid", "EB5693B95B204102B2E28C5624C6E9ED");

                    res = req.post(scheduleTableURL + "?sf_request_type=ajax",
                            RequestOptions.create()
                                    .setForm(formData)
                                    .setHeader("X-Requested-With", "XMLHttpRequest")
                                    .setHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                                    .setHeader("Referer", gatewayFirstEndURL)
                                    .setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"));
                }

                if (res.status() != 200) {
                    log.error("课程表请求失败: status={}, openId={}", res.status(), wxId);
                    throw new BusinessException(
                            SysReturnCode.BASE_PROXY.getCode(),
                            "课程表获取失败，请稍后重试",
                            ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                    );
                }

            } catch (com.microsoft.playwright.TimeoutError e) {
                log.error("课程表请求超时: openId={}, error={}", wxId, e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "教务系统响应超时，请稍后重试",
                        ResultCodeEnum.GATEWAY_TIMEOUT.getCode()
                );
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("课程表请求出错: openId={}, error={}", wxId, e.getMessage());
                throw new BusinessException(
                        SysReturnCode.BASE_PROXY.getCode(),
                        "课程表获取失败：" + e.getMessage(),
                        ResultCodeEnum.INTERNAL_SERVER_ERROR.getCode()
                );
            }

            // 更新 Cookie
            authSessionCacheUtil.saveOrUpdateSessionCookie(wxId, context.cookies());

            return crouseParser.parseCourseTable(res.text());
        }, browserPool.getDefaultTimeoutSeconds());
    }
}
