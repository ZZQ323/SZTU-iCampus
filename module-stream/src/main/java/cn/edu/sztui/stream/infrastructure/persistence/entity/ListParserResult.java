package cn.edu.sztui.stream.infrastructure.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 列表解析结果
 * <p>
 * 统一的列表页解析结果结构，适用于所有数据源
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListParserResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 解析出的条目列表
     */
    private List<InfoItemMeta> items;

    /**
     * 总页数（如果能解析到）
     */
    private Integer totalPage;

    /**
     * 总条数（如果能解析到）
     */
    private Long totalCount;

    /**
     * 当前页码
     */
    private Integer currentPage;

    /**
     * 信息条目元数据
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InfoItemMeta implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 文章ID，全局唯一
         */
        private String id;

        /**
         * 相对路径，如 info/1018/50731.htm
         */
        private String url;

        /**
         * 频道ID
         */
        private String channelId;

        /**
         * 数据源ID
         */
        private String sourceId;

        /**
         * 分类代码：1018/1019/1020/1021/1022
         */
        private String categoryCode;

        /**
         * 分类名称：教务/科研/行政/学工/校园
         */
        private String categoryName;

        /**
         * 发文单位/部门
         */
        private String department;

        /**
         * 标题
         */
        private String title;

        /**
         * 发文日期，格式 yyyy-MM-dd
         */
        private String publishDate;

        /**
         * 摘要（如果有）
         */
        private String summary;

        /**
         * 爬取时间戳
         */
        private Long crawledAt;

        /**
         * 额外属性
         */
        private java.util.Map<String, Object> extra;
    }
}