package cn.edu.sztui.base.application.vo;

import lombok.Data;

/**
 * 校历响应 VO
 * <p>
 * 每个学年包含春秋两个学期，每学期一张校历图。
 * imageUrl 已封装为后端代理 URL（/proxy/image?url=...），前端直接 img src 使用。
 */
@Data
public class CalendarVo {

    /** 学年，"YYYY-YYYY" 如 "2025-2026" */
    private String year;

    /** 秋季学期（第一学期），可能为 null */
    private SemesterVo autumn;

    /** 春季学期（第二学期），可能为 null */
    private SemesterVo spring;

    @Data
    public static class SemesterVo {
        /** 学期名，如 "春季学期" / "秋季学期"（来源于页面 h3 文本） */
        private String label;

        /** 代理后的图片 URL，形如 "/proxy/image?url=<encoded>" */
        private String imageUrl;
    }
}
