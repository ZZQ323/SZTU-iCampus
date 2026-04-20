package cn.edu.sztui.stream.application.activity.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则预筛的正反例单测。
 * <p>
 * 原则：宁可漏筛（送 LLM 多看几篇），不误杀活动。所以测试里"正例必须通过"比"负例必须拒绝"更严格。
 */
class ActivityPreFilterTest {

    private final ActivityPreFilter f = new ActivityPreFilter();

    // ==================== 正例：应当命中 ====================

    @Test
    void title_has_lecture_passes() {
        assertTrue(f.accepts("关于举办人工智能前沿讲座的通知", "内容略"));
    }

    @Test
    void title_has_competition_passes() {
        assertTrue(f.accepts("关于举办第五届程序设计竞赛的通知", ""));
    }

    @Test
    void title_has_recruitment_fair_passes() {
        assertTrue(f.accepts("2026 春季校园招聘会预告", ""));
    }

    @Test
    void title_has_welcome_sign_up_passes() {
        assertTrue(f.accepts("欢迎报名参加职业生涯规划沙龙", ""));
    }

    @Test
    void body_has_keyword_plus_date_passes() {
        assertTrue(f.accepts(
                "关于近期活动安排的说明",
                "学校将于2026年4月28日举办年度庆典，欢迎报名"
        ));
    }

    @Test
    void body_relative_date_plus_keyword_passes() {
        assertTrue(f.accepts(
                "关于近期活动的通知",
                "下周三下午在报告厅举办研讨会，欢迎大家参加"
        ));
    }

    // ==================== 负例：应当拒绝 ====================

    @Test
    void pure_policy_title_rejected() {
        assertFalse(f.accepts("关于加强作风建设的实施细则", "依据上级文件，现印发本规定..."));
    }

    @Test
    void blacklist_title_rejected_even_with_date() {
        assertFalse(f.accepts(
                "关于人事任命的公示",
                "张三同志自2026年4月28日起担任..."
        ));
    }

    @Test
    void empty_input_rejected() {
        assertFalse(f.accepts("", ""));
        assertFalse(f.accepts(null, null));
    }

    @Test
    void body_keyword_without_date_rejected() {
        // "举办" 出现但没日期 → 不放行
        assertFalse(f.accepts("关于某事项说明", "此前我们举办了..."));
    }

    @Test
    void date_without_keyword_rejected() {
        // 有日期但无活动关键词
        assertFalse(f.accepts("春季学期工作安排", "2026年3月1日起执行"));
    }

    // ==================== reason 字段 ====================

    @Test
    void judge_returns_matching_keyword() {
        String reason = f.judge("人工智能讲座通知", "");
        assertNotNull(reason);
        assertTrue(reason.startsWith("title:"));
    }

    @Test
    void judge_returns_body_hit() {
        String reason = f.judge("通知", "将于2026年4月28日举办活动");
        assertNotNull(reason);
        assertTrue(reason.startsWith("body:"));
    }
}
