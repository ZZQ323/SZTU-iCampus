package cn.edu.sztui.base.infrastructure.util.parser;

import cn.edu.sztui.base.application.vo.CourseTableVo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrouseParser 单元测试
 * <p>
 * 测试课表 HTML 解析的边界情况。
 * 完整的课表 HTML 样本需放在 infos/acdm/CrouseTable.htm（如有）。
 */
class CrouseParserTest {

    private final CrouseParser parser = new CrouseParser();

    @Test
    void parseCourseTable_noTimetableElement_returnsEmpty() {
        String html = "<html><body>no timetable here</body></html>";
        CourseTableVo vo = parser.parseCourseTable(html);

        assertNotNull(vo);
        assertNotNull(vo.getCourses());
        assertTrue(vo.getCourses().isEmpty());
    }

    @Test
    void parseCourseTable_emptyHtml_returnsEmpty() {
        CourseTableVo vo = parser.parseCourseTable("");
        assertNotNull(vo);
        assertNotNull(vo.getCourses());
        assertTrue(vo.getCourses().isEmpty());
    }

    @Test
    void parseCourseTable_tableWithoutCourses_returnsEmpty() {
        // 只有表头，没有课程内容
        String html = """
                <html><body>
                    <table id="timetable">
                        <tr><th></th><th>星期一</th></tr>
                        <tr>
                            <th>第一二节 08:30-10:00</th>
                            <td>&nbsp;</td>
                        </tr>
                    </table>
                </body></html>
                """;
        CourseTableVo vo = parser.parseCourseTable(html);

        assertNotNull(vo);
        assertNotNull(vo.getCourses());
        assertTrue(vo.getCourses().isEmpty(), "无课程内容时 courses 应为空");
    }

    @Test
    void parseCourseTable_singleCourse_parsesCorrectly() {
        // 最小的有课程的 HTML（模仿教务系统实际返回）
        String html = """
                <html><body>
                    <table id="timetable">
                        <tr>
                            <th></th>
                            <th>星期一</th>
                            <th>星期二</th>
                        </tr>
                        <tr>
                            <th>第一二节 08:30-10:00</th>
                            <td>&nbsp;</td>
                            <td>
                                <div id="ABC123-2-1" class="kbcontent1">
                                    <font>大学物理</font>
                                    <font title="教师">张三</font>
                                    <font title="周次(节次)">4-17(周)[01-02节]</font>
                                    <font title="教室">C-5-103</font>
                                </div>
                            </td>
                        </tr>
                    </table>
                </body></html>
                """;

        CourseTableVo vo = parser.parseCourseTable(html);

        assertNotNull(vo.getCourses());
        assertFalse(vo.getCourses().isEmpty(), "应至少解析出 1 门课");

        CourseTableVo.CourseInfo course = vo.getCourses().get(0);
        assertEquals("大学物理", course.getCourseName());
        assertEquals("张三", course.getTeacher());
        assertEquals("C-5-103", course.getLocation());
        assertEquals("4-17(周)", course.getCourseWeeks(), "周次应去掉 [节次] 部分");
        assertEquals("ABC123", course.getCourseId());
        assertEquals("08:30-10:00", course.getCourseTime());
    }
}
