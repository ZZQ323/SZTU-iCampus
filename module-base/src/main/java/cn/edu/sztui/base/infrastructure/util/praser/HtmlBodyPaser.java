package cn.edu.sztui.base.infrastructure.util.praser;

import cn.edu.sztui.base.application.vo.CourseTableVO;
import cn.edu.sztui.base.application.vo.CourseTableVO.CourseInfo;
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
public class HtmlBodyPaser
{
    // 时间正则：提取 08:30-09:55
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{2}:\\d{2}-\\d{2}:\\d{2})");

    /**
     * 解析课程表 HTML
     * @param htmlContent iframe 页面的 HTML 内容
     * @return CourseTableVO
     */
    public CourseTableVO parseCourseTable(String htmlContent) {
        CourseTableVO vo = new CourseTableVO();
        List<CourseInfo> courses = new ArrayList<>();

        Document doc = Jsoup.parse(htmlContent);
        Element table = doc.select("#timetable").first();

        if (table == null) {
            log.warn("未找到课程表");
            vo.setCourses(courses);
            return vo;
        }

        Elements rows = table.select("tbody > tr");

        // 跳过第一行表头，从第二行开始
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            Element row = rows.get(rowIndex);
            // 获取时间信息（从第一个 th 中）
            Element timeHeader = row.select("th").first();
            String timeSlot = extractTimeSlot(timeHeader);
            // 获取所有课程单元格（td）
            Elements cells = row.select("td");
            // 遍历每一列（周一到周日，7列）
            for (int colIndex = 0; colIndex < cells.size(); colIndex++) {
                Element cell = cells.get(colIndex);
                // 提取课程信息
                CourseInfo course = parseCourseCell(cell, rowIndex - 1, colIndex, timeSlot);
                if (course != null)courses.add(course);
            }
        }
        vo.setCourses(courses);
        log.info("解析到 {} 门课程", courses.size());
        return vo;
    }

    /**
     * 从 th 标签中提取时间信息
     */
    private String extractTimeSlot(Element timeHeader) {
        if (timeHeader == null) return "";
        String text = timeHeader.text();
        Matcher matcher = TIME_PATTERN.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    /**
     * 解析单个课程单元格
     */
    private CourseInfo parseCourseCell(Element cell, int row, int col, String timeSlot) {
        // 查找可见的课程内容 div（不包含 display:none）
        Elements visibleDivs = cell.select("div.kbcontent, div.kbcontent1");

        Element contentDiv = null;
        for (Element div : visibleDivs) {
            String style = div.attr("style");
            // 排除隐藏的 div
            if (!style.contains("display:none") && !style.contains("display: none")) {
                String text = div.html();
                // 排除空内容
                if (StringUtils.hasText(text) && !text.trim().equals("&nbsp;")) {
                    contentDiv = div;
                    break;
                }
            }
        }

        if (contentDiv == null) {
            return null;  // 该单元格无课程
        }

        // 提取课程ID（从 div 的 id 属性或 input 的 value）
        String courseId = contentDiv.attr("id");
        if (!StringUtils.hasText(courseId)) {
            Element input = cell.select("input[type=hidden]").first();
            courseId = input != null ? input.attr("value") : "";
        }

        // 解析课程详细信息（HTML 格式）
        return parseCourseContent(contentDiv.html(), row, col, courseId, timeSlot);
    }

    /**
     * 解析课程内容（通常格式为：课程名<br/>周次<br/>教室<br/>教师）
     */
    private CourseInfo parseCourseContent(String html, int row, int col, String courseId, String timeSlot) {
        CourseInfo info = new CourseInfo();
        info.setRow(row);
        info.setCol(col);
        info.setCourseId(courseId);
        info.setCourseTime(timeSlot);
        // 按 <br> 或换行符分割
        String[] lines = html.split("<br\\s*/?>|<br>|\\n");
        List<String> validLines = new ArrayList<>();
        for (String line : lines) {
            // 清理 HTML 标签和空白
            String cleaned = Jsoup.parse(line).text().trim();
            if (StringUtils.hasText(cleaned) && !cleaned.equals("&nbsp;")) {
                validLines.add(cleaned);
            }
        }
        if (validLines.isEmpty()) return null;

        // 根据实际格式解析（需要根据你的实际数据调整）
        // 常见格式：
        // 第1行：课程名称
        // 第2行：周次（如 "1-16周"）
        // 第3行：教室（如 "A101"）
        // 第4行：教师（如 "张老师"）

        if (validLines.size() >= 1)   info.setCourseName(validLines.get(0));
        if (validLines.size() >= 2)   info.setCourseWeeks(validLines.get(1));
        if (validLines.size() >= 3)  info.setLocation(validLines.get(2));
        if (validLines.size() >= 4)  info.setTeacher(validLines.get(3));
        return info;
    }

}
