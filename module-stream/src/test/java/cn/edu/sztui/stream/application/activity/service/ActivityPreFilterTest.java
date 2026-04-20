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

    // ==================== 迭代扩充后的正例（从 FN 来） ====================

    @Test
    void exchange_program_passes() {
        assertTrue(f.accepts("关于2026年秋季学期德国某大学交换项目报名的通知", ""));
    }

    @Test
    void training_camp_passes() {
        assertTrue(f.accepts("关于举办2026年深圳技术大学创新创业师资能力提升训练营的通知", ""));
    }

    @Test
    void recruit_passes() {
        assertTrue(f.accepts("关于2026年人工智能学院开源社区成员招募的通知", ""));
    }

    @Test
    void workshop_passes() {
        assertTrue(f.accepts("关于举办信息素养赋能工作坊（第一期）的通知", ""));
    }

    @Test
    void grand_competition_passes() {
        assertTrue(f.accepts("关于举办第十六届全国大学生电子商务创新创业挑战赛校赛遴选的通知", ""));
    }

    @Test
    void micro_major_admission_passes() {
        assertTrue(f.accepts("2026年第一届AI+供应链微专业招生通知", ""));
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

    // ==================== 迭代扩充后的负例（从 FP 来） ====================

    @Test
    void work_seminar_rejected_despite_seminar_keyword() {
        // "教学工作研讨会" 是内部会议不是活动，blacklist 里的"工作研讨会"比 whitelist "研讨会" 优先
        assertFalse(f.accepts("关于召开教学工作研讨会的通知", ""));
    }

    @Test
    void postdoc_review_rejected() {
        // 博士后考核报告会 = 学术例行，不是活动
        assertFalse(f.accepts("关于人工智能学院博士后出站考核报告会的通知", ""));
    }

    @Test
    void budget_application_rejected() {
        assertFalse(f.accepts("关于组织申报2026年度科技创新竞赛经费预算的通知", ""));
    }

    @Test
    void pre_notification_rejected() {
        assertFalse(f.accepts("关于开展谈话调研有关工作的预通知", "将于2026年4月28日开展..."));
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
