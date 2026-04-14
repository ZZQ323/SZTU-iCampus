package cn.edu.sztui.stream.application.external.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 爬取结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrawlResult {
    private String sourceId;
    private int newCount;
    private List<String> newIds;
    private String latestId;
    private boolean success;
    private String errorMessage;
    /** Cookie 认证失败（401/403/重定向到登录页），需要切换 Cookie 来源 */
    private boolean authError;
    /** 使用的 Cookie 来源用户 ID（用于失效时切换） */
    private String cookieUserId;

    public static CrawlResult success(String sourceId, int newCount, List<String> newIds, String latestId) {
        CrawlResult r = new CrawlResult();
        r.sourceId = sourceId;
        r.newCount = newCount;
        r.newIds = newIds;
        r.latestId = latestId;
        r.success = true;
        return r;
    }

    public static CrawlResult empty(String sourceId) {
        CrawlResult r = new CrawlResult();
        r.sourceId = sourceId;
        r.newCount = 0;
        r.newIds = List.of();
        r.success = true;
        return r;
    }

    public static CrawlResult fail(String sourceId, String error) {
        CrawlResult r = new CrawlResult();
        r.sourceId = sourceId;
        r.success = false;
        r.errorMessage = error;
        return r;
    }

    public static CrawlResult authFail(String sourceId, String cookieUserId, String error) {
        CrawlResult r = new CrawlResult();
        r.sourceId = sourceId;
        r.success = false;
        r.authError = true;
        r.cookieUserId = cookieUserId;
        r.errorMessage = error;
        return r;
    }
}