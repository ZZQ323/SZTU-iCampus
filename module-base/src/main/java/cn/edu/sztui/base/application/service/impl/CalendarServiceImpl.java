package cn.edu.sztui.base.application.service.impl;

import cn.edu.sztui.base.application.service.CalendarService;
import cn.edu.sztui.base.application.vo.CalendarVo;
import cn.edu.sztui.common.cache.util.CacheUtil;
import cn.edu.sztui.common.util.smarthttp.dto.SmartResponse;
import cn.edu.sztui.common.util.smarthttp.service.SmartHttpClient;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CalendarServiceImpl implements CalendarService {

    static final String BASE = "https://www.sztu.edu.cn";
    static final String PAGE_URL_FMT = BASE + "/xxgk/xxxl/a%s___%sxnd.htm";

    private static final Pattern YEAR_HREF = Pattern.compile("a(\\d{4})___(\\d{4})xnd\\.htm");
    private static final Pattern YEAR_INPUT = Pattern.compile("^(\\d{4})-(\\d{4})$");

    private static final String KEY_YEARS = "icampus:cache:calendar:years";
    private static final String KEY_PREFIX = "icampus:cache:calendar:";
    private static final long TTL_YEARS = 7L * 24 * 3600;
    private static final long TTL_CALENDAR = 30L * 24 * 3600;

    @Resource
    private SmartHttpClient smartHttpClient;

    @Resource
    private CacheUtil cacheUtil;

    // ==================== 对外接口 ====================

    @Override
    public List<String> getYears() {
        Object cached = cacheUtil.get(KEY_YEARS);
        if (cached != null) {
            return JSON.parseArray(cached.toString(), String.class);
        }

        String probeYear = currentAcademicYear();
        List<String> years = null;
        String body = fetchPageBody(probeYear);
        if (body != null) {
            years = parseYearList(body);
        }

        if (years == null || years.isEmpty()) {
            log.warn("[Calendar] year list fetch failed, falling back to probe year only: {}", probeYear);
            years = new ArrayList<>();
            years.add(probeYear);
        }

        cacheUtil.set(KEY_YEARS, JSON.toJSONString(years), TTL_YEARS);
        return years;
    }

    @Override
    public CalendarVo getCalendar(String year) {
        if (!isValidYear(year)) {
            throw new IllegalArgumentException("Invalid year: " + year);
        }

        String key = KEY_PREFIX + year;
        Object cached = cacheUtil.get(key);
        if (cached != null) {
            return JSON.parseObject(cached.toString(), CalendarVo.class);
        }

        String body = fetchPageBody(year);
        if (body == null) return null;
        CalendarVo vo = parseCalendar(body, year);
        if (vo != null) {
            cacheUtil.set(key, JSON.toJSONString(vo), TTL_CALENDAR);
        }
        return vo;
    }

    // ==================== HTTP ====================

    private String fetchPageBody(String year) {
        String url = String.format(PAGE_URL_FMT, year.substring(0, 4), year.substring(5));
        try {
            SmartResponse resp = smartHttpClient.get(url, smartHttpClient.newSession());
            if (resp == null || !resp.isSuccess()) {
                log.warn("[Calendar] fetch failed: {} status={}", url, resp == null ? -1 : resp.getStatusCode());
                return null;
            }
            return resp.getBody();
        } catch (Exception e) {
            log.warn("[Calendar] fetch error: {} {}", url, e.getMessage());
            return null;
        }
    }

    // ==================== 解析（静态方法，便于离线单测） ====================

    /** 从页面的 ul 列表提取所有学年 */
    static List<String> parseYearList(String html) {
        Document doc = Jsoup.parse(html);
        Elements links = doc.select("a[href]");
        TreeSet<String> set = new TreeSet<>(Comparator.reverseOrder());
        for (Element a : links) {
            Matcher m = YEAR_HREF.matcher(a.attr("href"));
            if (m.find()) {
                set.add(m.group(1) + "-" + m.group(2));
            }
        }
        return new ArrayList<>(set);
    }

    /** 从页面提取春/秋两学期的图 */
    static CalendarVo parseCalendar(String html, String year) {
        Document doc = Jsoup.parse(html);
        Elements blocks = doc.select("div.xl1");
        if (blocks.isEmpty()) return null;

        CalendarVo vo = new CalendarVo();
        vo.setYear(year);

        for (Element div : blocks) {
            String label = div.select("h3").text().trim();
            Element img = div.selectFirst("img[src]");
            if (img == null) continue;
            String src = img.attr("src").trim();
            if (src.isEmpty()) continue;

            String absUrl;
            if (src.startsWith("http://") || src.startsWith("https://")) {
                absUrl = src;
            } else if (src.startsWith("/")) {
                absUrl = BASE + src;
            } else {
                absUrl = BASE + "/xxgk/xxxl/" + src;
            }

            CalendarVo.SemesterVo semester = new CalendarVo.SemesterVo();
            semester.setLabel(label.isEmpty() ? null : label);
            semester.setImageUrl(toProxyUrl(absUrl));

            if (label.contains("春")) {
                vo.setSpring(semester);
            } else if (label.contains("秋")) {
                vo.setAutumn(semester);
            }
        }

        if (vo.getSpring() == null && vo.getAutumn() == null) return null;
        return vo;
    }

    static String toProxyUrl(String absUrl) {
        return "/proxy/image?url=" + URLEncoder.encode(absUrl, StandardCharsets.UTF_8);
    }

    // ==================== 辅助 ====================

    static boolean isValidYear(String year) {
        if (year == null) return false;
        Matcher m = YEAR_INPUT.matcher(year);
        if (!m.matches()) return false;
        int y1 = Integer.parseInt(m.group(1));
        int y2 = Integer.parseInt(m.group(2));
        return y2 == y1 + 1;
    }

    /** 当前学年（9 月后进入新学年） */
    static String currentAcademicYear() {
        LocalDate now = LocalDate.now();
        int startYear = (now.getMonthValue() >= 9) ? now.getYear() : now.getYear() - 1;
        return startYear + "-" + (startYear + 1);
    }
}
