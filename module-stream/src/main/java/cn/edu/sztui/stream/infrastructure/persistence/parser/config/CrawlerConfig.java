package cn.edu.sztui.stream.infrastructure.persistence.parser.config;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 爬虫配置 POJO
 * <p>
 * ⭐ 新增 listUrl 字段：CMS 页面用固定 URL（如 jdt.htm），不用 listUrlTemplate
 */
public class CrawlerConfig {

    @Data
    public static class ChannelConfig {
        private String id;
        private String name;
        private String description;
        private String icon;
        private Integer sort;
        private Boolean enabled;
        private List<String> sources;

        public List<String> getSourceIds() {
            return sources;
        }
    }

    @Data
    public static class SourceConfig {
        private String id;
        private String name;
        private String channelId;
        private String parserType;
        private String baseUrl;

        /**
         * 列表页 URL 模板（公文通 list.jsp 用，含 {page} 占位符）
         */
        private String listUrlTemplate;

        /**
         * ⭐ 新增：列表页固定 URL（CMS 页面用，分页靠 list2.htm / list3.htm 后缀）
         */
        private String listUrl;

        /**
         * 详情页 URL 模板
         */
        private String detailUrlTemplate;

        /**
         * 分类代码
         */
        private String categoryCode;

        /**
         * 分类名称
         */
        private String categoryName;

        private boolean requiresAuth;
        private Integer crawlIntervalMinutes;
        private Integer crawlPageCount;
        private boolean enabled;
        private Integer sort;

        /**
         * 扩展参数
         */
        private Map<String, String> extra;

        // ==================== 便捷方法 ====================

        public boolean isRequiresAuth() {
            return requiresAuth;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getCrawlInterval() {
            return crawlIntervalMinutes != null ? crawlIntervalMinutes : 10;
        }

        public int getPageCount() {
            return crawlPageCount != null ? crawlPageCount : 1;
        }

        /**
         * 返回分类映射（兼容 InfoServiceImpl）
         */
        public Map<String, String> getCategories() {
            if (categoryCode != null && categoryName != null) {
                return Map.of(categoryCode, categoryName);
            }
            return Map.of();
        }

        /**
         * 获取详情页 URL 模板（别名）
         */
        public String getDetailUrl() {
            return detailUrlTemplate;
        }
    }

    @Data
    public static class ChannelsRoot {
        private List<ChannelConfig> channels;
    }

    @Data
    public static class SourcesRoot {
        private List<SourceConfig> sources;
    }
}