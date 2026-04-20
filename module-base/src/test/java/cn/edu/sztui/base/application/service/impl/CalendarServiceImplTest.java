package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.vo.CalendarVo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CalendarServiceImpl 解析器离线单测
 * <p>
 * 样本放在 classpath:calendar-samples/*.htm（ASCII 文件名，避免 JVM 中文路径问题）
 */
class CalendarServiceImplTest {

    @Test
    void parseYearList_shouldExtractAllAcademicYears() throws IOException {
        String html = loadSample("calendar-2025-2026.htm");
        List<String> years = CalendarServiceImpl.parseYearList(html);

        assertFalse(years.isEmpty(), "year list should not be empty");
        assertTrue(years.contains("2025-2026"), "should contain current year");
        assertTrue(years.contains("2024-2025"));
        assertTrue(years.contains("2017-2018"), "should include the earliest known year");

        // 降序排列
        for (int i = 1; i < years.size(); i++) {
            assertTrue(years.get(i - 1).compareTo(years.get(i)) > 0,
                    "years must be in descending order: " + years);
        }
    }

    @Test
    void parseCalendar_2025_2026_hasBothSemesters() throws IOException {
        String html = loadSample("calendar-2025-2026.htm");
        CalendarVo vo = CalendarServiceImpl.parseCalendar(html, "2025-2026");

        assertNotNull(vo);
        assertEquals("2025-2026", vo.getYear());
        assertNotNull(vo.getSpring(), "spring semester missing");
        assertNotNull(vo.getAutumn(), "autumn semester missing");

        assertEquals("春季学期", vo.getSpring().getLabel());
        assertEquals("秋季学期", vo.getAutumn().getLabel());

        // 图片 URL 是代理形式
        assertTrue(vo.getSpring().getImageUrl().startsWith("/proxy/image?url="),
                "spring image not proxied: " + vo.getSpring().getImageUrl());
        assertTrue(vo.getAutumn().getImageUrl().startsWith("/proxy/image?url="));

        // 解出的绝对 URL 应指向 sztu.edu.cn
        String decodedSpring = URLDecoder.decode(
                vo.getSpring().getImageUrl().substring("/proxy/image?url=".length()),
                StandardCharsets.UTF_8);
        assertTrue(decodedSpring.startsWith("https://www.sztu.edu.cn/"),
                "spring abs url wrong: " + decodedSpring);
    }

    @Test
    void parseCalendar_2024_2025_hasBothSemesters() throws IOException {
        String html = loadSample("calendar-2024-2025.htm");
        CalendarVo vo = CalendarServiceImpl.parseCalendar(html, "2024-2025");

        assertNotNull(vo);
        assertEquals("2024-2025", vo.getYear());
        assertNotNull(vo.getSpring());
        assertNotNull(vo.getAutumn());
    }

    @Test
    void parseCalendar_emptyHtml_returnsNull() {
        assertNull(CalendarServiceImpl.parseCalendar("<html></html>", "2025-2026"));
    }

    @Test
    void isValidYear_acceptsYyyyDashYyyy() {
        assertTrue(CalendarServiceImpl.isValidYear("2025-2026"));
        assertTrue(CalendarServiceImpl.isValidYear("2017-2018"));
    }

    @Test
    void isValidYear_rejectsMalformed() {
        assertFalse(CalendarServiceImpl.isValidYear(null));
        assertFalse(CalendarServiceImpl.isValidYear("2025"));
        assertFalse(CalendarServiceImpl.isValidYear("2025/2026"));
        assertFalse(CalendarServiceImpl.isValidYear("2025-2030"),
                "non-consecutive years should be rejected");
    }

    private String loadSample(String name) throws IOException {
        var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("calendar-samples/" + name),
                "sample not found: " + name);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
