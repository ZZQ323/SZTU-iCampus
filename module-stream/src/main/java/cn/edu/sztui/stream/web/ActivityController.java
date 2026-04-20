package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.application.activity.service.ActivityIndexService;
import cn.edu.sztui.stream.application.activity.service.ActivityTimeParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动日历前端查询接口（公开，在 PUBLIC_PATHS 白名单里）。
 * <p>
 * 管理员侧的"扫描抽取"在 {@link ActivityAdminController}，那里才是写入索引的源头。
 * 本控制器只读。
 */
@Tag(name = "活动日历·查询")
@RestController
@RequestMapping("/activity")
public class ActivityController {

    /** 单次查询最多返回条数硬上限 */
    private static final int MAX_LIMIT = 500;

    @Resource
    private ActivityIndexService indexService;

    @Operation(summary = "即将到来的活动", description = "按时间升序返回未来的活动；includePast 可回溯历史")
    @GetMapping("/v1/upcoming")
    public Result upcoming(
            @Parameter(description = "最多返回条数，默认 20，硬上限 500")
            @RequestParam(defaultValue = "20") Integer limit,
            @Parameter(description = "true = 包含过期活动（默认只看 now-7d 之后）")
            @RequestParam(defaultValue = "false") Boolean includePast) {
        int n = clampLimit(limit);
        return Result.ok(indexService.queryUpcoming(n, Boolean.TRUE.equals(includePast)));
    }

    @Operation(summary = "按日期范围查询", description = "传 ISO 日期 from/to，闭区间")
    @GetMapping("/v1/list")
    public Result byRange(
            @Parameter(description = "起始日期 YYYY-MM-DD", required = true)
            @RequestParam String from,
            @Parameter(description = "结束日期 YYYY-MM-DD（含当日）", required = true)
            @RequestParam String to,
            @Parameter(description = "最多条数，默认 100，硬上限 500")
            @RequestParam(defaultValue = "100") Integer limit) {
        long fromMs = ActivityTimeParser.dateToEpochMillis(from);
        // to 当日最后一刻
        long toMs = ActivityTimeParser.dateToEpochMillis(to) + 24 * 3600 * 1000L - 1;
        return Result.ok(indexService.queryByRange(fromMs, toMs, clampLimit(limit)));
    }

    @Operation(summary = "时间待定活动", description = "LLM 判为活动但未给出可解析时间的条目")
    @GetMapping("/v1/pending")
    public Result pending(
            @RequestParam(defaultValue = "20") Integer limit) {
        return Result.ok(indexService.queryPending(clampLimit(limit)));
    }

    @Operation(summary = "索引规模统计", description = "调试用")
    @GetMapping("/v1/stats")
    public Result stats() {
        return Result.ok(indexService.stats());
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return 20;
        return Math.min(limit, MAX_LIMIT);
    }
}
