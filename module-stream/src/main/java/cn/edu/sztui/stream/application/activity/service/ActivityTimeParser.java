package cn.edu.sztui.stream.application.activity.service;

import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * LLM 返回的时间字符串 → 毫秒时间戳 的解析器。
 * <p>
 * 成功格式：
 * <ul>
 *   <li>{@code 2026-04-28T14:00} → 当日 14:00（北京时间）</li>
 *   <li>{@code 2026-04-28T14:00:00} → 同上</li>
 *   <li>{@code 2026-04-28} → 当日 00:00</li>
 * </ul>
 * <p>
 * 失败（返回 null）：空字符串、相对时间（"下周三"、"明天"）、非 ISO 格式。
 * 解析失败的活动进入"时间待定"列表，不上时间轴。
 */
public final class ActivityTimeParser {

    /** 校园默认时区 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private ActivityTimeParser() {}

    /**
     * @return 毫秒时间戳；无法解析返回 null
     */
    public static Long parseToEpochMillis(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim();

        // 兼容可能的秒字段
        try {
            if (s.contains("T")) {
                LocalDateTime dt = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return dt.atZone(ZONE).toInstant().toEpochMilli();
            }
            if (s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
                LocalDate d = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
                return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * 辅助：把一个 "YYYY-MM-DD" 转成当日 00:00 的毫秒时间戳。给查询端点的 from / to 用。
     */
    public static long dateToEpochMillis(String isoDate) {
        Long v = parseToEpochMillis(isoDate);
        if (v != null) return v;
        throw new IllegalArgumentException("Invalid date: " + isoDate);
    }
}
