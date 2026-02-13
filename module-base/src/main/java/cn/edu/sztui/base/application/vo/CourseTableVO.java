package cn.edu.sztui.base.application.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class CourseTableVO {

    private List<CourseInfo> courses;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseInfo {
        // 行位置（第几节课，0-based）
        private int row;
        // 列位置（星期几，0=周一，6=周日）
        private int col;
        // 课程id
        String courseId;
        // 课程名称
        String courseName;
        // 课程周次
        String courseWeeks;
        // 课程时间
        String courseTime;
        // 上课教室
        String location;
        // 教师（如果有）
        private String teacher;
        // 必修还是选修（暂无）
        // String courseType;
        // 课程状态（暂无）
        // String courseStatus;
    }
}
