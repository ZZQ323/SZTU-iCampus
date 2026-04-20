package cn.edu.sztui.stream.application.activity.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ActivityTimeParserTest {

    @Test
    void parses_iso_date_time() {
        Long ms = ActivityTimeParser.parseToEpochMillis("2026-04-28T14:00");
        assertNotNull(ms);
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms), ZoneId.of("Asia/Shanghai"));
        assertEquals(2026, dt.getYear());
        assertEquals(4, dt.getMonthValue());
        assertEquals(28, dt.getDayOfMonth());
        assertEquals(14, dt.getHour());
        assertEquals(0, dt.getMinute());
    }

    @Test
    void parses_iso_date_time_with_seconds() {
        assertNotNull(ActivityTimeParser.parseToEpochMillis("2026-04-28T14:00:00"));
    }

    @Test
    void parses_date_only_as_midnight() {
        Long ms = ActivityTimeParser.parseToEpochMillis("2026-04-28");
        assertNotNull(ms);
        LocalDateTime dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ms), ZoneId.of("Asia/Shanghai"));
        assertEquals(0, dt.getHour());
        assertEquals(0, dt.getMinute());
    }

    @Test
    void returns_null_for_empty() {
        assertNull(ActivityTimeParser.parseToEpochMillis(""));
        assertNull(ActivityTimeParser.parseToEpochMillis(null));
        assertNull(ActivityTimeParser.parseToEpochMillis("   "));
    }

    @Test
    void returns_null_for_relative_time() {
        // LLM 可能返回"下周三"、"明天"这种，我们主动丢弃不尝试解析
        assertNull(ActivityTimeParser.parseToEpochMillis("下周三"));
        assertNull(ActivityTimeParser.parseToEpochMillis("明天下午"));
        assertNull(ActivityTimeParser.parseToEpochMillis("待定"));
    }

    @Test
    void returns_null_for_garbage() {
        assertNull(ActivityTimeParser.parseToEpochMillis("abc"));
        assertNull(ActivityTimeParser.parseToEpochMillis("2026/04/28"));  // 非 ISO
        assertNull(ActivityTimeParser.parseToEpochMillis("04-28"));        // 缺年
    }

    @Test
    void dateToEpochMillis_helper_works() {
        long ms = ActivityTimeParser.dateToEpochMillis("2026-04-28");
        assertTrue(ms > 0);
    }

    @Test
    void dateToEpochMillis_throws_on_invalid() {
        assertThrows(IllegalArgumentException.class,
                () -> ActivityTimeParser.dateToEpochMillis("not a date"));
    }
}
