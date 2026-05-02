package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.application.activity.service.ActivityScanService;
import cn.edu.sztui.stream.application.activity.vo.ActivityExtractionVo;
import cn.edu.sztui.stream.application.activity.vo.ScanResultVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 活动抽取管理员接口（调试用）
 * <p>
 * 手动触发扫描最近 N 篇文章，看 LLM 返回结果对不对。不挂任何事件监听，
 * 爬虫新文章也不会自动处理。用户按需调用。
 * <p>
 * 认证：这些接口不在 CookieAuthFilter 的 PUBLIC_PATHS 里 → 必须带
 * X-School-Cookies header 才能调用（保护 DashScope 额度不被匿名打爆）。
 */
@Slf4j
@Tag(name = "活动抽取·管理")
@RestController
@RequestMapping("/admin/activity")
public class ActivityAdminController {

    @Resource
    private ActivityScanService scanService;

    @Resource
    private cn.edu.sztui.stream.application.activity.service.ActivityReportService reportService;

    @Resource
    private cn.edu.sztui.stream.application.activity.service.ActivityIndexService indexService;

    @Resource
    private cn.edu.sztui.stream.application.activity.service.ActivityBackfillService backfillService;

    @Value("${ai.activity.default-channels:announcement}")
    private List<String> defaultChannels;

    // ==================== Scan ====================

    @PostMapping("/scan-recent")
    @Operation(summary = "扫描最近 N 篇文章并跑活动抽取")
    public Result scanRecent(@RequestBody(required = false) ScanRequest req) {
        if (req == null) req = new ScanRequest();
        List<String> channels = (req.getChannels() == null || req.getChannels().isEmpty())
                ? defaultChannels : req.getChannels();
        int limit = req.getLimit() == null ? 10 : req.getLimit();
        boolean force = Boolean.TRUE.equals(req.getForce());
        boolean bypass = Boolean.TRUE.equals(req.getBypassPreFilter());

        log.info("[ActivityAdmin] scan-recent channels={} limit={} force={} bypassPreFilter={}",
                channels, limit, force, bypass);
        List<ScanResultVo> rows = scanService.scan(channels, limit, force, bypass);
        return Result.ok(rows);
    }

    /**
     * CSV 导出 —— 同一批扫描结果下载为 csv，Excel 打开。给老师看/论文截图用。
     */
    @GetMapping(value = "/scan-export", produces = "text/csv;charset=UTF-8")
    @Operation(summary = "扫描并导出为 CSV")
    public void scanExport(
            @RequestParam(required = false) String channels,
            @RequestParam(required = false, defaultValue = "10") Integer limit,
            @RequestParam(required = false, defaultValue = "false") Boolean force,
            @RequestParam(required = false, defaultValue = "false") Boolean bypassPreFilter,
            HttpServletResponse response) throws IOException {

        List<String> chList = (channels == null || channels.isBlank())
                ? defaultChannels : List.of(channels.split(","));
        log.info("[ActivityAdmin] scan-export channels={} limit={} force={} bypassPreFilter={}",
                chList, limit, force, bypassPreFilter);

        List<ScanResultVo> rows;
        try {
            rows = scanService.scan(chList, limit, force, bypassPreFilter);
        } catch (Exception e) {
            log.error("scan failed", e);
            rows = Collections.emptyList();
        }

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"activity-scan.csv\"");

        OutputStream out = response.getOutputStream();
        // UTF-8 BOM for Excel 中文兼容
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        StringBuilder sb = new StringBuilder();
        sb.append("articleId,channelId,sourceId,title,publishDate,passedPreFilter,preFilterReason,")
                .append("fromCache,calledAi,durationMs,tokens,isActivity,confidence,type,activityTitle,")
                .append("startAt,endAt,location,registration,summary,error\n");
        for (ScanResultVo r : rows) {
            sb.append(csvRow(r));
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ==================== 辅助 ====================

    private String csvRow(ScanResultVo r) {
        ActivityExtractionVo ai = r.getAiResult();
        int tokens = (r.getPromptTokens() == null ? 0 : r.getPromptTokens())
                + (r.getCompletionTokens() == null ? 0 : r.getCompletionTokens());
        return String.join(",",
                csv(r.getArticleId()),
                csv(r.getChannelId()),
                csv(r.getSourceId()),
                csv(r.getTitle()),
                csv(r.getPublishDate()),
                String.valueOf(r.isPassedPreFilter()),
                csv(r.getPreFilterReason()),
                String.valueOf(r.isFromCache()),
                String.valueOf(r.isCalledAi()),
                String.valueOf(r.getDurationMs()),
                String.valueOf(tokens),
                ai == null ? "" : String.valueOf(ai.isActivity()),
                ai == null ? "" : String.valueOf(ai.getConfidence()),
                csv(ai == null ? null : ai.getType()),
                csv(ai == null ? null : ai.getTitle()),
                csv(ai == null ? null : ai.getStartAt()),
                csv(ai == null ? null : ai.getEndAt()),
                csv(ai == null ? null : ai.getLocation()),
                csv(ai == null ? null : ai.getRegistration()),
                csv(ai == null ? null : ai.getSummary()),
                csv(r.getError())
        ) + "\n";
    }

    private String csv(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        if (escaped.contains(",") || escaped.contains("\"")) return "\"" + escaped + "\"";
        return escaped;
    }

    // ==================== User feedback (reports) ====================

    @GetMapping("/reports")
    @Operation(summary = "查看用户报告的活动识别错误", description = "论文'人机协同'章节数据源")
    public Result listReports(@RequestParam(defaultValue = "100") Integer limit) {
        return Result.ok(java.util.Map.of(
                "total", reportService.count(),
                "items", reportService.list(limit)
        ));
    }

    // ==================== Admin hide ====================

    @PostMapping("/hide")
    @Operation(summary = "管理员从索引里隐藏某条活动", description = "详情不删，查询排除。适合处理'LLM 误判但索引已写'的情况。")
    public Result adminHide(@RequestParam String articleId) {
        indexService.adminHide(articleId);
        return Result.ok();
    }

    @PostMapping("/unhide")
    @Operation(summary = "管理员恢复被隐藏的活动")
    public Result adminUnhide(@RequestParam String articleId) {
        indexService.adminUnhide(articleId);
        return Result.ok();
    }

    @GetMapping("/hidden")
    @Operation(summary = "已隐藏的活动 ID 列表")
    public Result adminHiddenList() {
        return Result.ok(indexService.listAdminHidden());
    }

    // ==================== 请求体 ====================

    // ==================== Backfill（全量补扫）====================

    /**
     * 全量补扫：把指定频道（或 default-channels）下 Redis 里所有现存文章都走一遍 LLM 抽取流程。
     * <p>
     * 区别于 {@code /scan-recent}（只扫最近 N 条，受 ai.activity.max-scan-count=200 限制），
     * 此接口分页扫整个 timeline，无上限。
     * <p>
     * <b>同步阻塞</b>—— N 大时调用方需要长时间等待。下次实现 jobId 异步化。
     * <p>
     * 已扫过的文章命中 LLM 30 天缓存，<b>不烧 token</b>；要重判请 bump
     * {@code ai.activity.cache-version}（如 v3 → v4）。
     */
    @PostMapping("/backfill")
    @Operation(summary = "全量补扫现有文章入活动索引")
    public Result backfill(@RequestBody(required = false) BackfillRequest req) {
        List<String> channels = (req == null || req.getChannels() == null || req.getChannels().isEmpty())
                ? null : req.getChannels();
        log.info("[ActivityAdmin] backfill channels={}", channels == null ? "default" : channels);
        var snapshot = backfillService.backfill(channels);
        return Result.ok(snapshot);
    }

    /** 查询 backfill 当前进度（运行时调用，非阻塞）*/
    @GetMapping("/backfill-status")
    @Operation(summary = "查看 backfill 进度")
    public Result backfillStatus() {
        return Result.ok(backfillService.currentProgress());
    }

    @Data
    public static class BackfillRequest {
        /** 频道 ID 列表；为空使用 ai.activity.default-channels（announcement / job）*/
        private List<String> channels;
    }

    @Data
    public static class ScanRequest {
        /** 频道 ID 列表；为空使用 ai.activity.default-channels */
        private List<String> channels;
        /** 每个频道取最近 N 篇，默认 10 */
        private Integer limit;
        /** true = 忽略缓存强制重跑 */
        private Boolean force;
        /**
         * true = 绕开规则预筛，所有文章都送 LLM（做"规则 vs 纯 LLM"对照实验用）。
         * 请注意会消耗更多 token —— 相当于把规则丢掉 80% 的"噪声文章"也送一遍 LLM。
         */
        private Boolean bypassPreFilter;
    }
}
