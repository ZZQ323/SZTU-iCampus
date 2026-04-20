package cn.edu.sztui.stream.application.activity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户对活动识别结果的纠错反馈（前端"报告错误"按钮提交）。
 * 论文"人机协同闭环"章节的数据素材。
 */
@Data
@NoArgsConstructor
public class ActivityReportVo {

    /** 被报告的文章 ID（必填） */
    private String articleId;

    /** 频道 ID（便于后续定位） */
    private String channelId;

    /** 报告原因枚举：not_activity / wrong_time / wrong_title / wrong_location / other */
    private String reason;

    /** 用户附加说明（可空） */
    private String note;

    /** 上报时的用户 ID（匿名访问为空） */
    private String userId;

    /** 上报时间（毫秒戳） */
    private Long reportedAt;

    /** 被报告时的活动标题（便于后台查看，不需要重新 join） */
    private String titleSnapshot;
}
