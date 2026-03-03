package cn.edu.sztui.base.application.vo;

import lombok.Data;
import java.util.List;

/**
 * 公告详情内容
 * <p>
 * 缓存在 Redis String 中，TTL=24h
 */
@Data
public class AnnouncementContentVo {

    /** 文章ID */
    private String id;

    /** 标题 */
    private String title;

    /** 作者（发文单位） */
    private String author;

    /** 发布时间 */
    private String publishTime;

    /** HTML 正文（已清洗） */
    private String content;

    /** 纯文本（用于搜索高亮） */
    private String plainText;

    /** 附件列表 */
    private List<Attachment> attachments;

    /** 上一篇 ID */
    private String prevId;

    /** 上一篇标题 */
    private String prevTitle;

    /** 下一篇 ID */
    private String nextId;

    /** 下一篇标题 */
    private String nextTitle;

    /** 缓存时间戳 */
    private Long cachedAt;

    @Data
    public static class Attachment {
        /** 附件名称 */
        private String name;
        /** 下载链接 */
        private String url;
    }
}