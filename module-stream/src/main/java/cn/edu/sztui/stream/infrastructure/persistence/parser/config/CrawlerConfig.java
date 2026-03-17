package cn.edu.sztui.stream.infrastructure.persistence.parser.config;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 爬虫配置 POJO
 * <p>
 * 从 YAML 文件加载的配置结构
 */
public class CrawlerConfig {

    /**
     * 频道配置
     * <p>
     * 对应 channels.yml
     */
    @Data
    public static class ChannelConfig {
        /** 频道ID，如 "announcement", "library", "job" */
        private String id;

        /** 频道名称，如 "校园公告", "图书馆", "招聘信息" */
        private String name;

        /** 频道描述 */
        private String description;

        /** 频道图标 */
        private String icon;

        /** 排序权重 */
        private Integer sort;

        /** 是否启用 */
        private Boolean enabled;

        /** 关联的数据源ID列表 */
        private List<String> sources;
    }

    /**
     * 数据源配置
     * <p>
     * 对应 sources.yml
     */
    @Data
    public static class SourceConfig {
        /** 数据源ID，全局唯一，如 "gwt-jiaowu", "gwt-keyan" */
        private String id;

        /** 数据源名称，如 "教务公告", "科研公告" */
        private String name;

        /** 所属频道ID */
        private String channelId;

        /** 解析器类型，对应 ParserStrategy.getType() */
        private String parserType;

        /** 基础URL */
        private String baseUrl;

        /** 列表页URL模板，支持 {page}, {category} 占位符 */
        private String listUrlTemplate;

        /** 详情页URL模板，支持 {id}, {category} 占位符 */
        private String detailUrlTemplate;

        /** 分类代码（如公文通的 1018/1019 等） */
        private String category;

        /** 分类名称 */
        private String categoryName;

        /** 是否需要登录态 */
        private Boolean requiresAuth;

        /** 爬取间隔（分钟） */
        private Integer crawlIntervalMinutes;

        /** 每次爬取的页数 */
        private Integer crawlPageCount;

        /** 是否启用 */
        private Boolean enabled;

        /** 排序权重 */
        private Integer sort;

        /** 扩展参数 */
        private Map<String, String> extra;

        // ==================== 便捷方法 ====================

        /**
         * 是否需要认证
         */
        public boolean isRequiresAuth() {
            return requiresAuth == null || requiresAuth;
        }

        /**
         * 是否启用
         */
        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        /**
         * 获取爬取间隔（默认10分钟）
         */
        public int getCrawlInterval() {
            return crawlIntervalMinutes != null ? crawlIntervalMinutes : 10;
        }

        /**
         * 获取每次爬取页数（默认1页）
         */
        public int getPageCount() {
            return crawlPageCount != null ? crawlPageCount : 1;
        }
    }

    /**
     * 频道列表配置文件根节点
     */
    @Data
    public static class ChannelsRoot {
        private List<ChannelConfig> channels;
    }

    /**
     * 数据源列表配置文件根节点
     */
    @Data
    public static class SourcesRoot {
        private List<SourceConfig> sources;
    }
}
