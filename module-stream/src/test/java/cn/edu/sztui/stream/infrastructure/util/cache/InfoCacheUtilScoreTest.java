package cn.edu.sztui.stream.infrastructure.util.cache;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * feed:timeline score 算法测试。
 * <p>
 * 关键论点：跨 source 的 id 不可比，按 publishDate 才能保证"全部来源最新"语义正确。
 */
class InfoCacheUtilScoreTest {

    @Test
    void parsePublishDate_supportsCommonFormats() {
        // ISO 标准
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026-04-30"));
        // 单数字月份
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026-4-30"));
        // 中文
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026年4月30日"));
        // 点分
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026.04.30"));
        // 斜杠
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026/04/30"));
        // 带时间后缀（忽略时间部分）
        assertEquals(expectEpochSec(2026, 4, 30),
                InfoCacheUtil.parsePublishDateEpochSec("2026-04-30 14:30"));
    }

    @Test
    void parsePublishDate_returnsNullForGarbage() {
        assertNull(InfoCacheUtil.parsePublishDateEpochSec(null));
        assertNull(InfoCacheUtil.parsePublishDateEpochSec(""));
        assertNull(InfoCacheUtil.parsePublishDateEpochSec("无日期"));
        assertNull(InfoCacheUtil.parsePublishDateEpochSec("2026-13-01"));      // 月超界
        assertNull(InfoCacheUtil.parsePublishDateEpochSec("2026-02-30"));      // 日超界
    }

    @Test
    void newerPublishDate_outranksLargerId() {
        // 老公文通：id 大，但发布早
        ListParserResult.InfoItemMeta announcement = meta("51999", "2025-09-10");
        // 新建学院文章：id 小，但发布晚
        ListParserResult.InfoItemMeta collegeDesign = meta("100", "2026-04-30");
        double sA = InfoCacheUtil.computeFeedScore(announcement);
        double sC = InfoCacheUtil.computeFeedScore(collegeDesign);
        assertTrue(sC > sA,
                "publishDate 新的 college-design 应该排前面，但 score(college)=" + sC + " <= score(announcement)=" + sA);
    }

    @Test
    void samePublishDate_tieBreakByCrawledAt() {
        ListParserResult.InfoItemMeta morning = metaWithCrawl("a", "2026-04-30", 1714435200000L);     // 当日 00:00 UTC 附近
        ListParserResult.InfoItemMeta evening = metaWithCrawl("b", "2026-04-30", 1714478400000L);     // 当日 12:00 UTC 附近
        double sM = InfoCacheUtil.computeFeedScore(morning);
        double sE = InfoCacheUtil.computeFeedScore(evening);
        assertNotEquals(sM, sE, "同 publishDate 不同 crawledAt 不应同 score");
    }

    @Test
    void missingPublishDate_fallsBackToCrawledAt() {
        ListParserResult.InfoItemMeta m = meta("123", null);
        m.setCrawledAt(1714435200000L);
        double s = InfoCacheUtil.computeFeedScore(m);
        assertEquals(1714435200000.0, s);
    }

    @Test
    void missingBoth_fallsBackToIdScore() {
        ListParserResult.InfoItemMeta m = new ListParserResult.InfoItemMeta();
        m.setId("12345");
        double s = InfoCacheUtil.computeFeedScore(m);
        assertEquals(12345.0, s);
    }

    @Test
    void nonNumericId_fallsBackToNegativeHash() {
        ListParserResult.InfoItemMeta m = new ListParserResult.InfoItemMeta();
        m.setId("ext_abc123");
        double s = InfoCacheUtil.computeFeedScore(m);
        assertTrue(s < 0, "非数字 id 且无日期，score 应为负数，实际=" + s);
    }

    // ==================== helpers ====================

    private static ListParserResult.InfoItemMeta meta(String id, String publishDate) {
        ListParserResult.InfoItemMeta m = new ListParserResult.InfoItemMeta();
        m.setId(id);
        m.setPublishDate(publishDate);
        return m;
    }

    private static ListParserResult.InfoItemMeta metaWithCrawl(String id, String publishDate, long crawledAt) {
        ListParserResult.InfoItemMeta m = meta(id, publishDate);
        m.setCrawledAt(crawledAt);
        return m;
    }

    private static long expectEpochSec(int y, int m, int d) {
        return LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
    }
}
