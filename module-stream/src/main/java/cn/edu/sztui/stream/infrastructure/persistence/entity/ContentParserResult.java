package cn.edu.sztui.stream.infrastructure.persistence.entity;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 详情解析结果（统一格式）
 *
 * 文件：module-base/src/main/java/cn/edu/sztui/base/infrastructure/persistence/parser/strategy/ContentParserResult.java
 */
@Data
public class ContentParserResult {

    /** 内容 ID */
    private String id;

    /** 标题 */
    private String title;

    /** HTML 正文 */
    private String content;

    /** 纯文本（搜索用） */
    private String plainText;

    /** 作者/发文单位 */
    private String author;

    /** 发布时间 */
    private String publishTime;

    /** 数据源 ID */
    private String sourceId;

    /** 来源名称 */
    private String sourceName;

    /** 频道 ID */
    private String channelId;

    /** 分类 ID */
    private String categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 附件列表 */
    private List<AttachmentInfo> attachments = new ArrayList<>();

    /** 上下篇导航 */
    private NavigationInfo navigation;

    /** 缓存时间戳 */
    private long cachedAt = System.currentTimeMillis();

    // ==================== 附件信息 ====================

    @Data
    public static class AttachmentInfo {
        /** 附件名称 */
        private String name;

        /** 下载链接 */
        private String url;

        /** 文件类型（pdf/word/excel/ppt/image/archive/file） */
        private String type;

        /** 文件大小（可选） */
        private String size;

        /**
         * 根据文件名推断类型
         */
        public static String inferType(String filename) {
            if (filename == null) return "file";

            String nameLower = filename.toLowerCase();
            if (nameLower.endsWith(".pdf")) return "pdf";
            if (nameLower.endsWith(".doc") || nameLower.endsWith(".docx")) return "word";
            if (nameLower.endsWith(".xls") || nameLower.endsWith(".xlsx")) return "excel";
            if (nameLower.endsWith(".ppt") || nameLower.endsWith(".pptx")) return "ppt";
            if (nameLower.endsWith(".zip") || nameLower.endsWith(".rar") || nameLower.endsWith(".7z")) return "archive";
            if (nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") ||
                    nameLower.endsWith(".png") || nameLower.endsWith(".gif")) return "image";

            return "file";
        }
    }

    // ==================== 导航信息 ====================

    @Data
    public static class NavigationInfo {
        /** 上一篇 ID */
        private String prevId;

        /** 上一篇标题 */
        private String prevTitle;

        /** 下一篇 ID */
        private String nextId;

        /** 下一篇标题 */
        private String nextTitle;
    }

    // ==================== 构建方法 ====================

    public void addAttachment(String name, String url) {
        AttachmentInfo att = new AttachmentInfo();
        att.setName(name);
        att.setUrl(url);
        att.setType(AttachmentInfo.inferType(name));
        attachments.add(att);
    }

    public void setNavigationInfo(String prevId, String prevTitle, String nextId, String nextTitle) {
        NavigationInfo nav = new NavigationInfo();
        nav.setPrevId(prevId);
        nav.setPrevTitle(prevTitle);
        nav.setNextId(nextId);
        nav.setNextTitle(nextTitle);
        this.navigation = nav;
    }

    /**
     * 是否有附件
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}