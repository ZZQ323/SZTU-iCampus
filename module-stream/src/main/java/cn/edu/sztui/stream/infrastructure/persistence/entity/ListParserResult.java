package cn.edu.sztui.stream.infrastructure.persistence.entity;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 列表解析结果（统一格式）
 *
 * 文件：module-base/src/main/java/cn/edu/sztui/base/infrastructure/persistence/parser/strategy/ListParserResult.java
 */
@Data
public class ListParserResult {

    /** 解析出的列表项 */
    private List<InfoItemMeta> items = new ArrayList<>();

    /** 总页数 */
    private int totalPage = 1;

    /** 当前页码 */
    private int currentPage = 1;

    /** 最新 ID（列表中第一条的 ID） */
    private String latestId;

    /** 是否有更多 */
    private boolean hasMore = true;

    /** 解析时间戳 */
    private long parsedAt = System.currentTimeMillis();

    // ==================== 列表项元数据 ====================

    @Data
    public static class InfoItemMeta {
        /** 内容 ID */
        private String id;

        /** 标题 */
        private String title;

        /** 数据源 ID */
        private String sourceId;

        /** 来源名称（如"教务处"、"研究生院"） */
        private String sourceName;

        /** 频道 ID */
        private String channelId;

        /** 分类 ID */
        private String categoryId;

        /** 分类名称（如"教务"、"科研"） */
        private String categoryName;

        /** 分类代码（如"1018"） */
        private String categoryCode;

        /** 发布日期（yyyy-MM-dd） */
        private String publishDate;

        /** 摘要（可选，新闻类用） */
        private String summary;

        /** 封面图（可选，新闻类用） */
        private String coverImage;

        /** 是否有附件 */
        private boolean hasAttachment;

        /** 详情页 URL（完整路径） */
        private String detailUrl;

        /** 原始 URL（相对路径，如 info/1018/50731.htm） */
        private String originalUrl;
    }

    // ==================== 构建方法 ====================

    public static ListParserResult empty() {
        ListParserResult result = new ListParserResult();
        result.setHasMore(false);
        return result;
    }

    public void addItem(InfoItemMeta item) {
        items.add(item);
        // 更新 latestId
        if (latestId == null || (item.getId() != null && compareId(item.getId(), latestId) > 0)) {
            latestId = item.getId();
        }
    }

    /**
     * 比较两个 ID（数字比较）
     */
    private int compareId(String id1, String id2) {
        try {
            return Long.compare(Long.parseLong(id1), Long.parseLong(id2));
        } catch (NumberFormatException e) {
            return id1.compareTo(id2);
        }
    }
}
