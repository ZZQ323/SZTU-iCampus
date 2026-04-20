package cn.edu.sztui.stream.application.activity.vo;

import lombok.Data;

/**
 * 管理员扫描一条文章后的结果行
 */
@Data
public class ScanResultVo {

    /** 文章 ID */
    private String articleId;

    private String channelId;

    private String sourceId;

    private String title;

    private String url;

    private String publishDate;

    /** 规则预筛是否命中（未命中的文章不会走 AI） */
    private boolean passedPreFilter;

    /** 预筛命中的关键词/原因，调试用 */
    private String preFilterReason;

    /** 是否走了 LLM（可能是缓存命中） */
    private boolean calledAi;

    /** 是否是缓存命中 */
    private boolean fromCache;

    /** AI 抽取结果，null 表示未调用或调用失败 */
    private ActivityExtractionVo aiResult;

    /** 调用耗时（毫秒） */
    private long durationMs;

    /** token 消耗（可能 null） */
    private Integer promptTokens;

    private Integer completionTokens;

    /** 错误信息（如有） */
    private String error;
}
