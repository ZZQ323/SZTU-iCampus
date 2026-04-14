package cn.edu.sztui.base.infrastructure.util.parser;

import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.application.vo.CourseTableVo.CourseInfo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务系统课程表 HTML 解析器
 */
@Slf4j
@Component
public class CrouseParser {

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}:\\d{2}-\\d{2}:\\d{2})");

    public CourseTableVo parseCourseTable(String html) {
        Document doc = Jsoup.parse(html);
        Element table = doc.getElementById("timetable");
        if (table == null) {
            log.warn("未找到课程表元素 #timetable");
            CourseTableVo vo = new CourseTableVo();
            vo.setCourses(new ArrayList<>());
            return vo;
        }
        Elements rows = table.select("tr");

        List<CourseInfo> courseList = new ArrayList<>();
        int rowIndex = -1;

        for (Element row : rows) {
            if (row.select("th").isEmpty()) {
                continue;
            }

            Element firstTh = row.select("th").first();
            String thText = firstTh.text().trim();

            if (thText.startsWith("备注:")) {
                continue;
            }

            String courseTime = extractTimeFromTh(thText);
            rowIndex++;
            Elements tds = row.select("td");
            int colIndex = 0;

            for (Element td : tds) {
                Elements visibleFonts = td.select("font:not([style*=display:none])");
                boolean hasValidContent = visibleFonts.stream()
                        .anyMatch(f -> StringUtils.hasText(f.text().trim()));

                if (hasValidContent) {
                    CourseInfo info = extractCourseInfo(td, rowIndex, colIndex, courseTime);
                    if (info != null) {
                        courseList.add(info);
                    }
                }
                colIndex++;
            }
        }

        CourseTableVo vo = new CourseTableVo();
        vo.setCourses(courseList);
        return vo;
    }

    private CourseInfo extractCourseInfo(Element td, int row, int col, String courseTime) {
        CourseInfo info = new CourseInfo();
        info.setRow(row);
        info.setCol(col);
        info.setCourseTime(courseTime);

        Element firstDiv = td.selectFirst("div[id]");
        if (firstDiv != null) {
            String divId = firstDiv.id();
            String courseId = divId.split("-")[0];
            info.setCourseId(courseId);
        }

        Element nameFont = td.selectFirst("font:not([title]):not([style*=display:none])");
        if (nameFont != null) {
            info.setCourseName(nameFont.text().trim());
        }

        Element teacherFont = td.selectFirst("font[title=教师]:not([style*=display:none])");
        if (teacherFont != null) {
            info.setTeacher(teacherFont.text().trim());
        }

        Element weekFont = td.selectFirst("font[title=周次(节次)]:not([style*=display:none])");
        if (weekFont != null) {
            String rawWeeks = weekFont.text().trim();
            info.setCourseWeeks(cleanWeeks(rawWeeks));
        }

        Element roomFont = td.selectFirst("font[title=教室]:not([style*=display:none])");
        if (roomFont != null) {
            info.setLocation(roomFont.text().trim());
        }

        return info;
    }

    private String extractTimeFromTh(String thText) {
        Matcher m = TIME_PATTERN.matcher(thText);
        return m.find() ? m.group(1) : "";
    }

    private String cleanWeeks(String rawWeeks) {
        if (rawWeeks.contains("[")) {
            return rawWeeks.substring(0, rawWeeks.indexOf("[")).trim();
        }
        return rawWeeks;
    }
}