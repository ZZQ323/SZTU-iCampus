package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.vo.CalendarVo;

import java.util.List;

/**
 * 校历服务
 * <p>
 * 数据来源：www.sztu.edu.cn/xxgk/xxxl/a{y1}___{y2}xnd.htm
 * 学年列表从页面左侧 ul 提取，图片从 div.xl1 提取。
 */
public interface CalendarService {

    /** 返回所有可选学年（如 "2025-2026"），降序排列 */
    List<String> getYears();

    /**
     * 按学年获取校历（春秋两学期图片）
     *
     * @param year 学年 "YYYY-YYYY"，如 "2025-2026"
     * @return 学年解析成功返回 VO；该学年页面不存在或解析失败返回 null
     */
    CalendarVo getCalendar(String year);
}
