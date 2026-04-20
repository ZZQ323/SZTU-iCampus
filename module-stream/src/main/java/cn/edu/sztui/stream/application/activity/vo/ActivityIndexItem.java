package cn.edu.sztui.stream.application.activity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动索引中的一条记录，写入 Redis + 返回给前端查询接口。
 * 由 {@link cn.edu.sztui.stream.application.activity.vo.ActivityExtractionVo}
 * 与原文章 meta 合成而来。
 */
@Data
@NoArgsConstructor
public class ActivityIndexItem {

    // ==================== 溯源 ====================

    /** 原文 ID，用于去详情页 */
    private String articleId;

    /** 原文所在频道 */
    private String channelId;

    /** 原文来源（source.id） */
    private String sourceId;

    /** 来源单位显示名（如"党委组织部"） */
    private String sourceOrgName;

    /** 原文 URL（可外跳打开）*/
    private String articleUrl;

    // ==================== LLM 抽取字段 ====================

    /** 活动类型：讲座/比赛/... */
    private String type;

    /** 活动标题（LLM 提炼后的；若为空则取原文标题）*/
    private String title;

    /** 开始时间字符串（原 LLM 输出） */
    private String startAt;

    /** 结束时间字符串 */
    private String endAt;

    private String location;

    private String registration;

    private String summary;

    private double confidence;

    // ==================== 派生 ====================

    /** 开始时间的毫秒戳；null 表示无法解析（该条进 pending 列表）*/
    private Long startAtEpoch;
}
