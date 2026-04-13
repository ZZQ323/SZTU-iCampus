package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 */
@Tag(name = "教务系统")
@RestController
@RequestMapping("/acdm")
public class AcademicController {

    @Autowired
    private AcademicService academicService;

    @Operation(summary = "初始化教务系统", description = "通过重定向链获取教务系统 cookie，返回更新后的 cookies")
    @GetMapping("/v1/refresh/cookies")
    public Result refreshCookies() {
        return Result.ok(academicService.init());
    }

    @Operation(summary = "获取课表", description = "获取课程表，支持指定周次和学期")
    @PostMapping("/v1/schedule")
    public Result getCrouseTable(@RequestBody CrouseTableQuery query) {
        return Result.ok(academicService.getCrouseTable(query));
    }
}
