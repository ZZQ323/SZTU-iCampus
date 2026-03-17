package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 详情内容解析结果
 * <p>
 * 通用的详情页解析结果，适用于所有数据源
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentParserResult {

    /** 是否解析成功 */
    private boolean success;

    /** 错误信息（如果失败） */
    private String errorMessage;

    /** 条目ID */
    private String id;

    /** 标题 */
    private String title;

    /** 作者/发文单位 */
    private String author;

    /** 发布时间 */
    private String publishTime;

    /** HTML 正文（已清洗） */
    private String htmlContent;

    /** 纯文本内容 */
    private String plainText;

    /** 附件列表 */
    private List<AttachmentInfo> attachments;

    /** 上一篇 ID */
    private String prevId;

    /** 上一篇标题 */
    private String prevTitle;

    /** 下一篇 ID */
    private String nextId;

    /** 下一篇标题 */
    private String nextTitle;

    /** 扩展字段 */
    private Map<String, Object> extra;

    /**
     * 附件信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentInfo {
        /** 附件名称 */
        private String name;
        /** 下载链接 */
        private String url;
        /** 文件大小 */
        private String size;
        /** 文件类型 */
        private String type;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建成功结果
     */
    public static ContentParserResult success(String id, String title, String htmlContent) {
        return ContentParserResult.builder()
                .success(true)
                .id(id)
                .title(title)
                .htmlContent(htmlContent)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static ContentParserResult fail(String errorMessage) {
        return ContentParserResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
