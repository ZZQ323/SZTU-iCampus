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
    void missingPublishDate_fallsBackToCrawledAt_inLowerTier() {
        ListParserResult.InfoItemMeta m = meta("123", null);
        m.setCrawledAt(1714435200000L);
        double s = InfoCacheUtil.computeFeedScore(m);
        assertEquals(InfoCacheUtil.TIER_CRAWLED + 1714435200000.0, s);
    }

    @Test
    void missingBoth_fallsBackToIdScore_inBottomTier() {
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

    /**
     * 核心 bug 回归：没 publishDate 但有 crawledAt 的"刚爬到的无日期文章"
     * 不能排到"几年前发布的真文章"前面。
     */
    @Test
    void crawledOnlyFallback_neverOutranksRealPublishDate() {
        // A：很老的真文章，2020-01-01 发布
        ListParserResult.InfoItemMeta veryOldButReal = meta("100", "2020-01-01");
        // B：刚刚爬到的无日期条目（publishDate 解析失败），crawledAt = 今天
        ListParserResult.InfoItemMeta freshButNoDate = meta("ext_xxx", null);
        freshButNoDate.setCrawledAt(System.currentTimeMillis());

        double sA = InfoCacheUtil.computeFeedScore(veryOldButReal);
        double sB = InfoCacheUtil.computeFeedScore(freshButNoDate);
        assertTrue(sA > sB,
                "publishDate 已知的老文章应排前面，但 score(real-old)=" + sA + " <= score(no-date-fresh)=" + sB);
    }

    @Test
    void tierOrdering_pubdate_above_crawled_above_fallback() {
        ListParserResult.InfoItemMeta withPub = meta("1", "1971-01-01");      // 最早可能的 publishDate
        ListParserResult.InfoItemMeta withCrawl = meta("2", null);
        withCrawl.setCrawledAt(System.currentTimeMillis() + 999L * 365 * 86_400_000L); // 极端未来 crawledAt
        ListParserResult.InfoItemMeta fallback = new ListParserResult.InfoItemMeta();
        fallback.setId("99999999");

        double s1 = InfoCacheUtil.computeFeedScore(withPub);
        double s2 = InfoCacheUtil.computeFeedScore(withCrawl);
        double s3 = InfoCacheUtil.computeFeedScore(fallback);
        assertTrue(s1 > s2, "publishDate 层 > crawledAt 层");
        assertTrue(s2 > s3, "crawledAt 层 > 兜底层");
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
