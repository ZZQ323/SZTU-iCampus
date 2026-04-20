package cn.edu.sztui.stream.application.activity.vo;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动抽取结果（LLM 返回的结构化 JSON）
 * <p>
 * 所有字符串字段可能为空串（非活动时大部分为空，活动可能缺某个字段）。
 * 时间字段格式："YYYY-MM-DDTHH:mm" 或 "YYYY-MM-DD"，空串表示未给出。
 * <p>
 * 注意字段名：用 {@code activity}（非 {@code isActivity}），避免 Lombok 对 "is" 前缀 boolean
 * 的 setter 生成特殊处理导致 FastJSON2 反序列化失败。外部 JSON key 仍为 "isActivity"（通过
 * {@link JSONField} 映射）。
 */
@Data
@NoArgsConstructor
public class ActivityExtractionVo {

    /** 是否是活动（JSON 字段名：isActivity） */
    @JSONField(name = "isActivity")
    private boolean activity;

    /** 置信度 0-1 */
    private double confidence;

    /** 活动类型：讲座/沙龙/比赛/招聘会/演出/展览/会议/报告会/其他；非活动为空 */
    private String type;

    /** 活动标题（可能与文章标题不同，LLM 会提炼） */
    private String title;

    /** 开始时间，ISO 8601 本地时间；未知或非活动为空 */
    @JSONField(name = "startAt")
    private String startAt;

    /** 结束时间；未知或非活动为空 */
    @JSONField(name = "endAt")
    private String endAt;

    /** 地点 */
    private String location;

    /** 报名方式 */
    private String registration;

    /** 两句话摘要 */
    private String summary;
}
