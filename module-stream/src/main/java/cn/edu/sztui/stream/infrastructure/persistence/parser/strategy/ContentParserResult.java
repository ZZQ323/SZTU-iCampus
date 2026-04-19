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
 * ⭐ 字段名与前端 InfoContent 完全一致：
 *    content（不是 htmlContent）、attachments、prevId/prevTitle/nextId/nextTitle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentParserResult {

    private boolean success;
    private String errorMessage;

    /** 条目ID */
    private String id;

    /** 标题 */
    private String title;

    /** 作者/发文单位 */
    private String author;

    /** 发布时间 */
    private String publishTime;

    /** ⭐ HTML 正文 — 改名：htmlContent → content，与前端 InfoContent.content 对齐 */
    private String content;

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

    /**
     * 外链 URL：当条目指向非 sztu.edu.cn 域名（如微信公众号、政府网站）时填此字段，
     * 前端据此渲染"跳转到外部浏览器"按钮而非正文。此时 content 可为空。
     */
    private String externalUrl;

    /** 扩展字段 */
    private Map<String, Object> extra;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentInfo {
        private String name;
        private String url;
        private String size;
        private String type;
    }

    public static ContentParserResult success(String id, String title, String content) {
        return ContentParserResult.builder()
                .success(true)
                .id(id)
                .title(title)
                .content(content)
                .build();
    }

    public static ContentParserResult fail(String errorMessage) {
        return ContentParserResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}