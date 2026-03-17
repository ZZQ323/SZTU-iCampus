package cn.edu.sztui.stream.infrastructure.persistence.parser.crouse;

import cn.edu.sztui.stream.infrastructure.persistence.entity.tableDTO.CourseTableVo;
import cn.edu.sztui.stream.infrastructure.persistence.entity.tableDTO.CourseTableVo.CourseInfo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CrouseParser
{

    // 匹配时间格式，如 08:30-10:00
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}:\\d{2}-\\d{2}:\\d{2})");

    public CourseTableVo parseCourseTable(String html) {
        Document doc = Jsoup.parse(html);
        Element table = doc.getElementById("timetable");  // 定位课程表
        Elements rows = table.select("tr");

        List<CourseInfo> courseList = new ArrayList<>();
        int rowIndex = -1;  // 从0开始计数数据行，表头不计数

        for (Element row : rows) {
            // 跳过表头行（第一行）
            if (row.select("th").isEmpty()) {
                continue;
            }

            // 获取该行第一个th（节次信息）
            Element firstTh = row.select("th").first();
            String thText = firstTh.text().trim();

            // 跳过备注行（以“备注:”开头）
            if (thText.startsWith("备注:")) {
                continue;
            }

            // 提取课程时间（如 08:30-10:00）
            String courseTime = extractTimeFromTh(thText);

            rowIndex++;
            Elements tds = row.select("td");  // 周一至周日共7个td
            int colIndex = 0;

            for (Element td : tds) {
                // 【避开无效信息】只选择可见且非空的font标签
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

    /**
     * 从单个td中提取完整的课程信息
     */
    private CourseInfo extractCourseInfo(Element td, int row, int col, String courseTime) {
        CourseInfo info = new CourseInfo();
        info.setRow(row);
        info.setCol(col);
        info.setCourseTime(courseTime);

        // 1. 课程ID：取第一个div的id属性，分割出UUID部分
        Element firstDiv = td.selectFirst("div[id]");
        if (firstDiv != null) {
            String divId = firstDiv.id();
            String courseId = divId.split("-")[0];
            info.setCourseId(courseId);
        }

        // 2. 课程名称：取可见的、无title属性的font（且不是隐藏的）
        Element nameFont = td.selectFirst("font:not([title]):not([style*=display:none])");
        if (nameFont != null) {
            String courseName = nameFont.text().trim();
            info.setCourseName(courseName);
        }

        // 3. 教师：font[title="教师"]
        Element teacherFont = td.selectFirst("font[title=教师]:not([style*=display:none])");
        if (teacherFont != null) {
            info.setTeacher(teacherFont.text().trim());
        }

        // 4. 周次：font[title="周次(节次)"]，去除多余的节次信息
        Element weekFont = td.selectFirst("font[title=周次(节次)]:not([style*=display:none])");
        if (weekFont != null) {
            String rawWeeks = weekFont.text().trim();
            info.setCourseWeeks(cleanWeeks(rawWeeks));
        }

        // 5. 教室：font[title="教室"]
        Element roomFont = td.selectFirst("font[title=教室]:not([style*=display:none])");
        if (roomFont != null) {
            info.setLocation(roomFont.text().trim());
        }

        return info;
    }

    /**
     * 从节次标题中提取时间范围
     */
    private String extractTimeFromTh(String thText) {
        Matcher m = TIME_PATTERN.matcher(thText);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 去除周次字符串中的节次后缀，例如 "1-5,7-13,15-16,18(周)[09-10节]" -> "1-5,7-13,15-16,18(周)"
     */
    private String cleanWeeks(String rawWeeks) {
        if (rawWeeks.contains("[")) {
            return rawWeeks.substring(0, rawWeeks.indexOf("[")).trim();
        }
        return rawWeeks;
    }
}