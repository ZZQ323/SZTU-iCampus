package cn.edu.sztui.stream.application.service;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一信息流服务接口
 * <p>
 * 支持多频道（channel）的信息获取
 */
public interface InfoService {

    // ==================== 频道与分类 ====================

    /**
     * 获取频道列表（带未读数）
     */
    List<ChannelInfo> getChannelsWithUnread();

    /**
     * 获取指定频道的分类代码映射
     */
    Map<String, String> getCategoryCodeMap(String channelId);

    // ==================== 内容列表 ====================

    /**
     * 获取信息列表
     *
     * @param channelId    频道ID
     * @param categoryCode 分类代码（可选）
     * @param page         页码
     * @param pageSize     每页数量
     * @return 列表结果
     */
    InfoListResult getList(String channelId, String categoryCode, int page, int pageSize);

    /**
     * 全局 Feed 查询（跨频道聚合，支持多维筛选）
     *
     * @param sourceIds 订阅模式：逗号分隔的 sourceId 列表；非空时只返回这些 source 的文章，
     *                  与 sourceOrg/channelId 等筛选维度叠加（AND）生效
     */
    InfoListResult getFeed(String sourceOrg, String channelId, String contentType, String subContentType,
                           String sourceIds, int page, int pageSize);

    /**
     * 搜索信息
     *
     * @param keyword   关键词
     * @param channelId 频道ID（可选，为空则搜索全部）
     * @param limit     最大返回数量
     * @return 搜索结果
     */
    List<ListParserResult.InfoItemMeta> search(String keyword, String channelId, int limit);

    // ==================== 内容详情 ====================

    /**
     * 获取详情
     *
     * @param channelId    频道ID
     * @param id           内容ID
     * @param categoryCode 分类代码（可选）
     * @return 详情内容
     */
    ContentParserResult getDetail(String channelId, String id, String categoryCode);

    // ==================== 未读管理 ====================

    /**
     * 获取各频道未读计数
     */
    Map<String, Long> getUnreadCounts();

    /**
     * 标记频道已读
     *
     * @param channelId 频道ID
     * @param latestId  已读到的最新ID
     */
    void markChannelRead(String channelId, String latestId);

    // ==================== DTO ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ChannelInfo {
        private String id;
        private String name;
        private String description;
        private String icon;
        /** 来源组织分类：fixed/official/department/support/league/college */
        private String sourceOrg;
        /** 该频道包含的内容类型（从源自动推导） */
        private List<String> contentTypes;
        private Integer sort;
        private Long unreadCount;
        private List<CategoryInfo> categories;
        /** 该频道下的数据源列表（前端订阅管理用） */
        private List<SourceInfo> sources;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class SourceInfo {
        private String id;
        private String name;
        private String contentType;
        private String subContentType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CategoryInfo {
        private String code;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class InfoListResult {
        private List<ListParserResult.InfoItemMeta> items;
        private String latestId;
        private Long total;
        private Boolean hasMore;
    }
}