package cn.edu.sztui.stream.application.activity.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 活动候选文章的规则预筛。
 * <p>
 * 定位：送 LLM 前的轻量筛选，目标是**丢掉绝大多数明显不是活动的文章**，
 * 节省 token。宁可漏判（让 AI 多看几篇）也不要误杀活动。
 * <p>
 * 规则（OR 命中任一即放行）：
 * <ul>
 *   <li>标题含活动关键词（讲座/沙龙/比赛/...）</li>
 *   <li>正文同时含「活动关键词 + 日期样式」</li>
 * </ul>
 * <p>
 * 黑名单关键词在标题出现则强制拒绝（典型的非活动内容，如"规定"、"办法"）。
 */
@Component
public class ActivityPreFilter {

    /**
     * 活动关键词 —— 在标题里出现就很可能是活动预告。
     * 基于 112 条人工标注数据的 FN 分析迭代扩充：
     *   原版漏了 交换项目 / 训练营 / 招募 / 工作坊 / 大赛 / 校赛 等。
     */
    private static final List<String> TITLE_KEYWORDS = List.of(
            // 讲座类
            "讲座", "讲坛", "沙龙", "论坛", "研讨会", "报告会", "分享会", "读书会",
            // 比赛类
            "比赛", "竞赛", "大赛", "校赛", "决赛", "初赛", "复赛", "辩论赛",
            // 招聘类
            "招聘会", "宣讲会", "面试会", "招新", "招募",
            // 活动/项目类
            "活动预告", "欢迎参加", "欢迎报名", "邀请函", "开放日", "校园开放",
            "训练营", "工作坊", "研修班", "交换项目", "暑期项目", "微专业",
            // 文体类
            "开学典礼", "毕业典礼", "音乐会", "晚会", "运动会", "观影",
            "展览", "演出", "文化节", "艺术节",
            // 通用"报名"触发（标题直接带报名，一般是活动）
            "报名"
    );

    /** 正文活动关键词 —— 在正文里出现配合日期才放行 */
    private static final List<String> BODY_KEYWORDS = List.of(
            "举办", "主办", "承办", "将于", "报名", "参加", "参赛",
            "邀请", "欢迎", "时间：", "地点：", "报名方式", "截止日期"
    );

    /**
     * 黑名单关键词 —— 标题含这些强制拒绝。
     * 基于 FP 分析迭代：工作研讨会 / 考核报告会 / 博士后xx 都被规则误放行了。
     * 注意 {@link #judge} 先检查黑名单再看白名单，所以这里的词可以和白名单片段重叠
     * （例如"考核报告会"比"报告会"更具体，优先级更高）。
     */
    private static final List<String> TITLE_BLACKLIST = List.of(
            // 行政程序类
            "规定", "管理办法", "工作规程", "规章制度", "实施细则", "实施方案",
            "公示", "通报", "表彰", "任命", "人事", "公告",
            // 学术例行事务类（非活动，就是内部流程）
            "工作研讨会", "考核报告", "开题", "结题", "出站", "博士后",
            "经费预算", "项目申报", "申报材料",
            // 预通知/调研（不是真活动）
            "谈话调研", "预通知", "征求意见"
    );

    /** 日期 */
    private static final Pattern DATE_PATTERNS = Pattern.compile(
            "\\d{4}年\\d{1,2}月\\d{1,2}日" +
            "|\\d{1,2}月\\d{1,2}日" +
            "|\\d{4}-\\d{1,2}-\\d{1,2}" +
            "|\\d{1,2}/\\d{1,2}" +
            "|下?周[一二三四五六日天]" +
            "|明[天日]" +
            "|今[天日晚]"
    );

    /**
     * 预筛决定。
     *
     * @return 命中原因，null 表示未命中（不送 LLM）
     */
    public String judge(String title, String content) {
        if (!StringUtils.hasText(title) && !StringUtils.hasText(content)) {
            return null;
        }

        String t = title == null ? "" : title;

        // 黑名单强制拒绝
        for (String bad : TITLE_BLACKLIST) {
            if (t.contains(bad)) return null;
        }

        // 标题命中活动关键词 → 直接放行
        for (String kw : TITLE_KEYWORDS) {
            if (t.contains(kw)) return "title:" + kw;
        }

        // 正文命中活动关键词 + 日期 → 放行
        String c = content == null ? "" : content;
        if (c.length() > 8000) c = c.substring(0, 8000);  // 预筛只看前 8k 够了

        boolean hasBodyKw = false;
        String matchedBodyKw = null;
        for (String kw : BODY_KEYWORDS) {
            if (c.contains(kw)) {
                hasBodyKw = true;
                matchedBodyKw = kw;
                break;
            }
        }
        if (hasBodyKw && DATE_PATTERNS.matcher(c).find()) {
            return "body:" + matchedBodyKw + "+date";
        }

        return null;
    }

    public boolean accepts(String title, String content) {
        return judge(title, content) != null;
    }
}
