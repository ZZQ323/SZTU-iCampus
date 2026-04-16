package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.base.application.vo.LoginResultsVo;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 教务系统控制器
 * <p>
 * 需要认证（header 必须携带 X-School-Cookies）：
 * <ul>
 *   <li>GET  /acdm/v1/refresh/cookies - 初始化教务系统，获取教务 cookie</li>
 *   <li>POST /acdm/v1/schedule        - 获取课表</li>
 * </ul>
 * <p>
 * ⭐ 修复：两个接口都通过 X-Set-Cookies header 返回更新后的 cookies。
 * 教务系统的重定向链会产生新 cookie，前端必须收到并保存。
 */
@Tag(name = "教务系统")
@RestController
@RequestMapping("/acdm")
public class AcademicController {

    private static final String HEADER_SET_COOKIES = "X-Set-Cookies";

    @Autowired
    private AcademicService academicService;

    @Operation(summary = "初始化教务系统", description = "通过重定向链获取教务系统 cookie，返回更新后的 cookies")
    @GetMapping("/v1/refresh/cookies")
    public Result refreshCookies(HttpServletResponse response) {
        LoginResultsVo result = academicService.init();
        // ⭐ 通过 header 返回 cookies（前端拦截器自动存储）
        if (result != null && result.getCookiesJson() != null) {
            response.setHeader(HEADER_SET_COOKIES, result.getCookiesJson());
        }
        return Result.ok(result);
    }

    @Operation(summary = "获取课表", description = "获取课程表，支持指定周次和学期。不传参数则返回当前学期第一周。")
    @PostMapping("/v1/schedule")
    public Result getCrouseTable(@RequestBody(required = false) CrouseTableQuery query,
                                  HttpServletResponse response) {
        // 默认参数：当前学期第一周
        if (query == null) {
            query = new CrouseTableQuery();
        }
        Object result = academicService.getCrouseTable(query);
        return Result.ok(result);
    }
}
