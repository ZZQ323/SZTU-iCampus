package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy;

import cn.edu.sztui.common.util.smarthttp.SmartSession;
import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;

/**
 * 解析器策略接口
 * <p>
 * 所有数据源解析器都需要实现此接口
 */
public interface ParserStrategy {

    /**
     * 获取解析器类型标识
     * <p>
     * 对应 sources.yml 中的 parserType 字段
     *
     * @return 解析器类型，如 "sztu-gwt", "sztu-library", "sztu-job"
     */
    String getType();

    /**
     * 解析列表页
     *
     * @param html         列表页 HTML 内容
     * @param sourceConfig 数据源配置
     * @param page         当前页码
     * @return 解析结果
     */
    ListParserResult parseList(String html, SourceConfig sourceConfig, int page);

    /**
     * 解析详情页
     *
     * @param html         详情页 HTML 内容
     * @param sourceConfig 数据源配置
     * @param itemId       条目ID
     * @return 解析结果
     */
    ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId);

    /**
     * 构建列表页 URL
     *
     * @param sourceConfig 数据源配置
     * @param page         页码
     * @return 完整的列表页 URL
     */
    default String buildListUrl(SourceConfig sourceConfig, int page) {
        String template = sourceConfig.getListUrlTemplate();
        if (template == null) {
            return sourceConfig.getBaseUrl();
        }
        return template
                .replace("{page}", String.valueOf(page))
                .replace("{category}", sourceConfig.getCategory() != null ? sourceConfig.getCategory() : "");
    }

    /**
     * 构建详情页 URL
     *
     * @param sourceConfig 数据源配置
     * @param itemId       条目ID
     * @param itemUrl      条目相对URL（如果有）
     * @return 完整的详情页 URL
     */
    default String buildDetailUrl(SourceConfig sourceConfig, String itemId, String itemUrl) {
        if (itemUrl != null && itemUrl.startsWith("http")) {
            return itemUrl;
        }
        String template = sourceConfig.getDetailUrlTemplate();
        if (template != null) {
            return template
                    .replace("{id}", itemId)
                    .replace("{category}", sourceConfig.getCategory() != null ? sourceConfig.getCategory() : "");
        }
        // 如果没有模板，尝试拼接
        String baseUrl = sourceConfig.getBaseUrl();
        if (itemUrl != null) {
            if (baseUrl.endsWith("/")) {
                return baseUrl + itemUrl;
            }
            return baseUrl + "/" + itemUrl;
        }
        return baseUrl + "/" + itemId;
    }

    /**
     * 是否需要登录态（Cookie）
     *
     * @return true 需要登录态，false 不需要
     */
    default boolean requiresAuth() {
        return true;
    }

    /**
     * 解析前的预处理（可选）
     * <p>
     * 比如某些网站需要先访问一个页面设置 Cookie
     *
     * @param session      HTTP 会话
     * @param sourceConfig 数据源配置
     */
    default void preProcess(SmartSession session, SourceConfig sourceConfig) {
        // 默认不做任何预处理
    }
}
