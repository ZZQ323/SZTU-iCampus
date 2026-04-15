package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 列表解析结果
 * <p>
 * 通用的列表页解析结果，适用于所有数据源
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListParserResult {

    /** 是否解析成功 */
    private boolean success;

    /** 错误信息（如果失败） */
    private String errorMessage;

    /** 解析出的条目列表 */
    private List<InfoItemMeta> items;

    /** 总页数 */
    private Integer totalPages;

    /** 总条目数 */
    private Integer totalCount;

    /** 当前页码 */
    private Integer currentPage;

    /** 是否有下一页 */
    private Boolean hasMore;

    /** 数据源ID */
    private String sourceId;

    /**
     * 通用信息条目元数据
     * <p>
     * 适用于公告、新闻、通知等各种信息类型
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InfoItemMeta {

        /** 条目ID，全局唯一 */
        private String id;

        /** 详情页URL（相对或绝对） */
        private String url;

        /** 标题 */
        private String title;

        /** 分类代码 */
        private String categoryCode;

        /** 分类名称 */
        private String categoryName;

        /** 发布单位/部门 */
        private String department;

        /** 发布日期，格式 yyyy-MM-dd */
        private String publishDate;

        /** 作者 */
        private String author;

        /** 摘要 */
        private String summary;

        /** 来源（数据源名称） */
        private String source;

        /** 频道ID */
        private String channelId;

        /** 数据源ID（如 kyb-tzgg） */
        private String sourceId;

        /** 内容大类（notice/news） */
        private String contentType;

        /** 内容细分类（general-news/party/cooperation/academic/student/general-notice/admission/employment） */
        private String subContentType;

        /** 来源组织分类（fixed/official/department/support/league/college） */
        private String sourceOrg;

        /** 来源组织名称（如 "科研部"、"中德智能制造学院"） */
        private String sourceOrgName;

        /** 爬取时间戳 */
        private Long crawledAt;

        /** 扩展字段（JSON 格式存储） */
        private String extra;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建成功结果
     */
    public static ListParserResult success(List<InfoItemMeta> items, Integer totalPages) {
        return ListParserResult.builder()
                .success(true)
                .items(items)
                .totalPages(totalPages)
                .hasMore(totalPages != null && totalPages > 1)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static ListParserResult fail(String errorMessage) {
        return ListParserResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
