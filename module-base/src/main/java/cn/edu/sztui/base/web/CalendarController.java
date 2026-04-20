package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.service.CalendarService;
import cn.edu.sztui.base.application.vo.CalendarVo;
import cn.edu.sztui.common.util.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校历控制器
 * <p>
 * 公开接口（无需 cookie），走 /calendar 路径（已在 CookieAuthFilter 白名单）。
 */
@Tag(name = "校历")
@RestController
@RequestMapping("/calendar")
public class CalendarController {

    @Resource
    private CalendarService calendarService;

    @Operation(summary = "学年列表", description = "返回可选学年（降序），如 ['2025-2026','2024-2025',...]")
    @GetMapping("/v1/years")
    public Result getYears() {
        return Result.ok(calendarService.getYears());
    }

    @Operation(summary = "某学年校历", description = "返回春秋两学期的校历图（已走 /proxy/image 代理）")
    @GetMapping("/v1/{year}")
    public Result getCalendar(
            @Parameter(description = "学年 'YYYY-YYYY'，如 '2025-2026'")
            @PathVariable("year") String year) {
        CalendarVo vo = calendarService.getCalendar(year);
        return Result.ok(vo);
    }
}
