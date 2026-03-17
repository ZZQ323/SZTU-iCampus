package cn.edu.sztui.stream.infrastructure.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 详情页解析结果
 * <p>
 * 统一的详情页解析结果结构，适用于所有数据源
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentParserResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文章ID
     */
    private String id;

    /**
     * 频道ID
     */
    private String channelId;

    /**
     * 数据源ID
     */
    private String sourceId;

    /**
     * 标题
     */
    private String title;

    /**
     * 发文单位/部门
     */
    private String department;

    /**
     * 发文日期
     */
    private String publishDate;

    /**
     * 正文内容（HTML）
     */
    private String content;

    /**
     * 纯文本内容（可选）
     */
    private String plainText;

    /**
     * 附件列表
     */
    private List<Attachment> attachments;

    /**
     * 上一篇ID
     */
    private String prevId;

    /**
     * 下一篇ID
     */
    private String nextId;

    /**
     * 阅读量（如果有）
     */
    private Long viewCount;

    /**
     * 额外属性
     */
    private java.util.Map<String, Object> extra;

    /**
     * 附件信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 文件名
         */
        private String name;

        /**
         * 下载链接
         */
        private String url;

        /**
         * 文件大小（如 "2.3MB"）
         */
        private String size;

        /**
         * 文件类型（如 "pdf", "docx"）
         */
        private String type;
    }
}