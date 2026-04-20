package cn.edu.sztui.stream.application.activity.service;

import cn.edu.sztui.common.cache.redis.RedisKeyGenerator;
import cn.edu.sztui.stream.application.activity.vo.ActivityReportVo;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用户活动纠错反馈的收集与读取。
 * <p>
 * 数据结构：
 * <ul>
 *   <li>{@code icampus:cache:activity:reports} —— LIST，LPUSH 新反馈；保持 1000 条以内 (LTRIM)</li>
 *   <li>每条 JSON：{@link ActivityReportVo}</li>
 * </ul>
 */
@Slf4j
@Service
public class ActivityReportService {

    private static final String LIST_KEY = "icampus:cache:activity:reports";
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
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RedisKeyGenerator redisKeyGenerator;

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

        String fullKey = redisKeyGenerator.generate(LIST_KEY);
        redisTemplate.opsForList().leftPush(fullKey, JSON.toJSONString(report));
        redisTemplate.opsForList().trim(fullKey, 0, MAX_REPORTS - 1);

        log.info("[ActivityReport] received: articleId={} reason={} userId={}",
                report.getArticleId(), report.getReason(), report.getUserId());
    }

    public List<ActivityReportVo> list(int limit) {
        if (limit <= 0) limit = 100;
        if (limit > (int) MAX_REPORTS) limit = (int) MAX_REPORTS;

        String fullKey = redisKeyGenerator.generate(LIST_KEY);
        List<Object> raw = redisTemplate.opsForList().range(fullKey, 0, limit - 1);
        if (raw == null || raw.isEmpty()) return List.of();

        List<ActivityReportVo> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            ActivityReportVo vo = JSON.parseObject(o.toString(), ActivityReportVo.class);
            if (vo != null) out.add(vo);
        }
        return out;
    }

    public long count() {
        String fullKey = redisKeyGenerator.generate(LIST_KEY);
        Long n = redisTemplate.opsForList().size(fullKey);
        return n == null ? 0 : n;
    }
}
