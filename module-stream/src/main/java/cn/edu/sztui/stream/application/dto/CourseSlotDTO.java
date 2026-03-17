package cn.edu.sztui.stream.application.dto;

import lombok.Data;

@Data
public class CourseSlotDTO {
    private int dayOfWeek;      // 1-7 (星期一到星期日)
    private String timeSlot;    // "第1 2节 08:30-09:55"
    private String courseInfo;  // 课程信息
}
