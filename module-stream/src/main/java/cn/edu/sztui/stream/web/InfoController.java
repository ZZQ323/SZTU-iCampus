package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.application.service.InfoService;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一信息流接口
 * <p>
 * 文件：module-base/src/main/java/cn/edu/sztui/base/web/InfoController.java
 * <p>
 * 说明：
 * - 替代原有的 AnnouncementController，提供更通用的接口
 * - 原有 /annc/* 接口保持兼容，内部调用此服务
 */
@Slf4j
@RestController
@RequestMapping("/info")
@Tag(name = "信息流接口", description = "统一的信息获取接口")
public class InfoController {

    @Resource
    private InfoService infoService;

    @Resource
    private CrawlerConfigLoader configLoader;

    @Resource
    private InfoCacheUtil infoCacheUtil;

    // ==================== 分类目录 ====================

    /**
     * 获取完整分类树
     * <p>
     * 包含：频道列表、各频道下的分类
     * 前端缓存此数据，作为筛选器的选项
     */
    @GetMapping("/v1/category-tree")
    @Operation(summary = "获取分类树", description = "获取频道、分类的完整配置")
    public Result getCategoryTree() {
        log.debug("用户 {} 获取分类树", UserContext.getContext().getUserId());
        return Result.ok(configLoader.getCategoryTree());
    }

    /**
     * 获取频道列表（带未读数）
     */
    @GetMapping("/v1/channels")
    @Operation(summary = "获取频道列表")
    public Result getChannels() {
        log.debug("用户 {} 获取频道列表", UserContext.getContext().getUserId());
        return Result.ok(infoService.getChannelsWithUnread());
    }

    /**
     * 获取分类列表（兼容现有接口）
     */
    @GetMapping("/v1/categories")
    @Operation(summary = "获取分类列表", description = "获取指定频道的分类代码映射")
    public Result getCategories(
            @Parameter(description = "频道ID，默认 announcement")
            @RequestParam(defaultValue = "announcement") String channelId) {
        log.debug("用户 {} 获取分类列表: channel={}", UserContext.getContext().getUserId(), channelId);
        return Result.ok(infoService.getCategoryCodeMap(channelId));
    }

    // ==================== 内容列表 ====================

    /**
     * 获取信息列表（统一入口）
     *
     * @param channelId    频道 ID（必填，默认 announcement）
     * @param categoryCode 分类代码（可选，如 "1018"）
     * @param page         页码
     * @param pageSize     每页数量
     */
    @GetMapping("/v1/list")
    @Operation(summary = "获取信息列表")
    public Result getList(
            @Parameter(description = "频道ID")
            @RequestParam(defaultValue = "announcement") String channelId,
            @Parameter(description = "分类代码（如 1018）")
            @RequestParam(required = false) String categoryCode,
            @Parameter(description = "页码")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量")
            @RequestParam(defaultValue = "20") Integer pageSize) {

        log.info("用户 {} 获取信息列表: channel={}, category={}, page={}",
                UserContext.getContext().getUserId(), channelId, categoryCode, page);

        InfoService.InfoListResult result = infoService.getList(channelId, categoryCode, page, pageSize);
        return Result.ok(result);
    }

    /**
     * 搜索（标题关键词）
     */
    @GetMapping("/v1/search")
    @Operation(summary = "搜索信息")
    public Result search(
            @Parameter(description = "搜索关键词", required = true)
            @RequestParam String keyword,
            @Parameter(description = "频道ID")
            @RequestParam(required = false) String channelId,
            @Parameter(description = "最大返回数量")
            @RequestParam(defaultValue = "20") Integer limit) {

        log.info("用户 {} 搜索: keyword={}, channel={}",
                UserContext.getContext().getUserId(), keyword, channelId);

        List<ListParserResult.InfoItemMeta> result = infoService.search(keyword, channelId, limit);
        return Result.ok(result);
    }

    // ==================== 内容详情 ====================

    /**
     * 获取详情
     *
     * @param channelId    频道 ID
     * @param id           内容 ID
     * @param categoryCode 分类代码（可选，用于构建 URL）
     */
    @GetMapping("/v1/detail/{channelId}/{id}")
    @Operation(summary = "获取信息详情")
    public Result getDetail(
            @Parameter(description = "频道ID", required = true)
            @PathVariable String channelId,

            @Parameter(description = "内容ID", required = true)
            @PathVariable String id,
            @Parameter(description = "分类代码")
            @RequestParam(required = false) String categoryCode) {

        log.info("用户 {} 获取详情: channel={}, id={}, category={}",
                UserContext.getContext().getUserId(), channelId, id, categoryCode);

        ContentParserResult result = infoService.getDetail(channelId, id, categoryCode);
        return Result.ok(result);
    }

    /**
     * 获取详情（简化路径，兼容现有接口）
     */
    @GetMapping("/v1/detail/{id}")
    @Operation(summary = "获取信息详情（简化）")
    public Result getDetailSimple(
            @Parameter(description = "内容ID", required = true)
            @PathVariable String id,
            @Parameter(description = "分类代码")
            @RequestParam(required = false) String categoryCode) {

        return getDetail("announcement", id, categoryCode);
    }

    // ==================== 未读管理 ====================

    /**
     * 获取未读计数（按频道）
     */
    @GetMapping("/v1/unread")
    @Operation(summary = "获取未读计数")
    public Result getUnreadCount() {
        log.debug("用户 {} 获取未读计数", UserContext.getContext().getUserId());
        return Result.ok(infoService.getUnreadCounts());
    }

    /**
     * 获取最新 ID（用于前端判断是否有新内容）
     */
    @GetMapping("/v1/latest")
    @Operation(summary = "获取最新ID")
    public Result getLatestId(
            @Parameter(description = "频道ID")
            @RequestParam(defaultValue = "announcement") String channelId) {

        String latestId = infoCacheUtil.getLatestId(channelId);
        Map<String, String> result = new HashMap<>();
        result.put("channelId", channelId);
        result.put("latestId", latestId != null ? latestId : "0");
        return Result.ok(result);
    }

    /**
     * 批量获取所有频道的最新 ID（前端 init 时一次拉取）
     */
    @GetMapping("/v1/latest-all")
    @Operation(summary = "批量获取所有频道最新ID")
    public Result getLatestAll() {
        Map<String, String> result = new HashMap<>();
        for (var channel : configLoader.getEnabledChannels()) {
            String latestId = infoCacheUtil.getLatestId(channel.getId());
            result.put(channel.getId(), latestId != null ? latestId : "0");
        }
        return Result.ok(result);
    }

    /**
     * 标记已读
     */
    @PostMapping("/v1/mark-read")
    @Operation(summary = "标记已读")
    public Result markRead(@RequestBody MarkReadRequest request) {
        log.debug("用户 {} 标记已读: channel={}, latestId={}",
                UserContext.getContext().getUserId(), request.getChannelId(), request.getLatestId());

        infoService.markChannelRead(
                request.getChannelId() != null ? request.getChannelId() : "announcement",
                request.getLatestId()
        );
        return Result.ok();
    }

    @Data
    public static class MarkReadRequest {
        private String channelId;
        private String latestId;
    }

    // ==================== 系统状态 ====================

    /**
     * 获取系统状态
     */
    @GetMapping("/v1/status")
    @Operation(summary = "获取系统状态")
    public Result getStatus() {
        Map<String, Object> status = new HashMap<>();

        // 配置信息
        status.put("channelCount", configLoader.getChannels().size());
        status.put("sourceCount", configLoader.getSources().size());

        // 缓存统计
        status.put("cacheStats", infoCacheUtil.getCacheStats());

        return Result.ok(status);
    }
}