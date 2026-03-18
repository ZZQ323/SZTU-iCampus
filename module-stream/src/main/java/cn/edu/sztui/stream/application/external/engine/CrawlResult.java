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
}