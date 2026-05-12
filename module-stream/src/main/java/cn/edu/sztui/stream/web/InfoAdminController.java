package cn.edu.sztui.stream.web;

import cn.edu.sztui.common.util.result.Result;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfigLoader;
import cn.edu.sztui.stream.infrastructure.util.cache.InfoCacheUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信息流·管理员维护端点。
 * <p>
 * 当前只有一个动作：按新 score 算法重建 <code>feed:timeline</code> ZSET。
 * <p>
 * 触发场景：score 算法从"按 id"改为"按 publishDate"后，老数据的 score 还是旧值，
 * "全部来源"页 ranking 是错的。跑一次这个端点就能在不重爬学校的前提下修好。
 * <p>
 * 认证：和 ActivityAdminController 一样，必须带 X-School-Cookies。
 */
@Slf4j
@Tag(name = "信息流·管理")
@RestController
@RequestMapping("/admin/info")
public class InfoAdminController {

    @Resource
    private InfoCacheUtil infoCacheUtil;

    @Resource
    private CrawlerConfigLoader crawlerConfigLoader;

    @PostMapping("/rebuild-feed-timeline")
    @Operation(summary = "按新算法（publishDate 优先）重写 feed:timeline 的 score")
    public Result rebuildFeedTimeline(@RequestParam(required = false) String channelId) {
        Map<String, Integer> perChannel = new LinkedHashMap<>();
        int total = 0;
        if (channelId != null && !channelId.isBlank()) {
            int n = infoCacheUtil.rebuildFeedTimelineForChannel(channelId);
            perChannel.put(channelId, n);
            total = n;
        } else {
            for (var ch : crawlerConfigLoader.getChannels()) {
                int n = infoCacheUtil.rebuildFeedTimelineForChannel(ch.getId());
                perChannel.put(ch.getId(), n);
                total += n;
            }
        }
        log.info("[admin/rebuild-feed-timeline] total={} perChannel={}", total, perChannel);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("perChannel", perChannel);
        return Result.ok(resp);
    }
}
