package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.stream.application.activity.vo.ActivityReportVo;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用户活动纠错反馈的收集与读取。
 * <p>
 * <b>所有 Redis 读写均经 {@link CacheUtil}</b>（项目硬规则）。
 * 数据结构：
 * <ul>
 *   <li>raw key {@code activity:reports} → cacheUtil 加前缀后落
 *       {@code dev:sztu:cache:activity:reports} —— LIST，LPUSH 新反馈，
 *       LTRIM 保留 1000 条</li>
 *   <li>每条 JSON：{@link ActivityReportVo}</li>
 * </ul>
 */
@Slf4j
@Service
public class ActivityReportService {

    private static final String LIST_KEY = "activity:reports";
    /** 保留最近 1000 条反馈，再老的自动淘汰 */
    private static final long MAX_REPORTS = 1000;

    /** 合法 reason 枚举，防止前端乱传 */
    private static final Set<String> VALID_REASONS = Set.of(
            "not_activity",     // 这不是活动
            "wrong_time",       // 时间错了
            "wrong_title",      // 标题错了
            "wrong_location",   // 地点错了
            "other"             // 其他问题
    );

    @Resource
    private CacheUtil cacheUtil;

    public void save(ActivityReportVo report) {
        if (report == null || !StringUtils.hasText(report.getArticleId())) {
            throw new IllegalArgumentException("articleId 不能为空");
        }
        if (!VALID_REASONS.contains(report.getReason())) {
            throw new IllegalArgumentException("reason 无效：" + report.getReason());
        }
        if (report.getReportedAt() == null) {
            report.setReportedAt(System.currentTimeMillis());
        }

        cacheUtil.lLeftPush(LIST_KEY, JSON.toJSONString(report));
        cacheUtil.lTrim(LIST_KEY, 0, MAX_REPORTS - 1);

        log.info("[ActivityReport] received: articleId={} reason={} userId={}",
                report.getArticleId(), report.getReason(), report.getUserId());
    }

    public List<ActivityReportVo> list(int limit) {
        if (limit <= 0) limit = 100;
        if (limit > (int) MAX_REPORTS) limit = (int) MAX_REPORTS;

        List<Object> raw = cacheUtil.lRange(LIST_KEY, 0, limit - 1);
        if (raw == null || raw.isEmpty()) return List.of();

        List<ActivityReportVo> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            ActivityReportVo vo = JSON.parseObject(o.toString(), ActivityReportVo.class);
            if (vo != null) out.add(vo);
        }
        return out;
    }

    public long count() {
        Long n = cacheUtil.lSize(LIST_KEY);
        return n == null ? 0 : n;
    }
}
