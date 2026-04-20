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

    /** 活动关键词 —— 在标题里出现就很可能是活动预告 */
    private static final List<String> TITLE_KEYWORDS = List.of(
            "讲座", "沙龙", "比赛", "竞赛", "报告会", "招聘会", "宣讲会",
            "活动预告", "欢迎参加", "欢迎报名", "邀请函", "开放日", "校园开放",
            "论坛", "研讨会", "分享会", "读书会", "观影", "展览", "演出",
            "音乐会", "晚会", "运动会", "招新", "面试会"
    );

    /** 正文活动关键词 —— 在正文里出现配合日期才放行 */
    private static final List<String> BODY_KEYWORDS = List.of(
            "举办", "主办", "承办", "将于", "报名", "参加", "参赛",
            "邀请", "欢迎", "时间：", "地点：", "报名方式", "截止日期"
    );

    /** 黑名单关键词 —— 标题含这些强制拒绝，即便有活动字样也不送 LLM */
    private static final List<String> TITLE_BLACKLIST = List.of(
            "规定", "管理办法", "工作规程", "规章制度", "实施细则",
            "公示", "通报", "表彰", "任命", "人事"
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
