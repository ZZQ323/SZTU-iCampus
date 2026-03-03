package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.service.AnnouncementService;
import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AnnouncementListVo;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import cn.edu.sztui.base.infrastructure.util.cache.AnnouncementCacheUtil;
import cn.edu.sztui.base.infrastructure.util.praser.AnnouncementListParser;
import cn.edu.sztui.common.util.auth.UserContext;
import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.infrastructure.cookie.CookieSourceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/annc")
@Tag(name = "公告接口", description = "校园公文通公告相关接口")
public class AnnouncementController {

    @Resource
    private AnnouncementService announcementService;

    @Resource
    private AnnouncementCacheUtil announcementCacheUtil;

    @Resource
    private cn.edu.sztui.stream.infrastructure.cookie.CookieSourceManager cookieSourceManager;

    // ==================== 列表查询 ====================

    /**
     * 获取公告列表
     */
    @GetMapping("/v1/list")
    @Operation(summary = "获取公告列表", description = "分页获取公告列表，支持按分类筛选")
    public Result getList(
            @Parameter(description = "分类代码：1018/1019/1020/1021/1022")
            @RequestParam(required = false) String category,
            @Parameter(description = "页码，默认1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量，默认20")
            @RequestParam(defaultValue = "20") Integer pageSize) {

        AnnouncementListVo vo = announcementService.getList(category, page, pageSize);
        return Result.ok(vo);
    }

    /**
     * 获取增量公告
     */
    @GetMapping("/v1/incremental")
    @Operation(summary = "获取增量公告", description = "获取比指定ID更新的公告列表")
    public Result getIncremental(
            @Parameter(description = "上次已读的最新ID", required = true)
            @RequestParam String lastId) {

        List<AnnouncementMetaVo> list = announcementService.getIncremental(lastId);
        return Result.ok(list);
    }

    /**
     * 获取最新公告ID
     */
    @GetMapping("/v1/latest")
    @Operation(summary = "获取最新公告ID", description = "用于前端判断是否有新公告")
    public Result getLatestId() {
        String latestId = announcementCacheUtil.getLatestId();
        Map<String, String> result = new HashMap<>();
        result.put("latestId", latestId != null ? latestId : "0");
        return Result.ok(result);
    }

    // ==================== 详情查询 ====================

    /**
     * 获取公告详情
     */
    @GetMapping("/v1/detail/{id}")
    @Operation(summary = "获取公告详情", description = "获取公告的完整内容")
    public Result getDetail(
            @Parameter(description = "公告ID", required = true)
            @PathVariable String id) {

        String openId = UserContext.getContext().getOpenId();
        AnnouncementContentVo content = announcementService.getDetail(openId, id);
        return Result.ok(content);
    }

    // ==================== 搜索 ====================

    /**
     * 标题搜索
     */
    @GetMapping("/v1/search")
    @Operation(summary = "标题搜索", description = "在缓存中按标题关键词搜索公告")
    public Result searchByTitle(
            @Parameter(description = "搜索关键词", required = true)
            @RequestParam String keyword,
            @Parameter(description = "最大返回数量，默认20")
            @RequestParam(defaultValue = "20") Integer limit) {

        List<AnnouncementMetaVo> list = announcementService.searchByTitle(keyword, limit);
        return Result.ok(list);
    }

    // ==================== 元信息 ====================

    /**
     * 获取分类列表
     */
    @GetMapping("/v1/categories")
    @Operation(summary = "获取分类列表", description = "获取所有公告分类及其代码")
    public Result getCategories() {
        return Result.ok(AnnouncementListParser.getCategoryMap());
    }

    /**
     * 获取系统状态
     */
    @GetMapping("/v1/status")
    @Operation(summary = "获取系统状态", description = "获取公告系统当前状态")
    public Result getSystemStatus() {
        Map<String, Object> status = new HashMap<>();

        // 系统状态
        CookieSourceManager.CookieSourceStatus sourceStatus = cookieSourceManager.getStatus();
        status.put("initialized", sourceStatus.isInitialized());
        status.put("operational", sourceStatus.isOperational());

        // 缓存统计
        status.put("totalCount", announcementCacheUtil.getTotalCount());
        status.put("latestId", announcementCacheUtil.getLatestId());
        status.put("lastCrawlTime", announcementCacheUtil.getLastCrawlTime());

        return Result.ok(status);
    }
}
