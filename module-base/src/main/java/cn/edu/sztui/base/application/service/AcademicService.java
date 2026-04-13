package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.vo.CourseTableVo;
import cn.edu.sztui.base.application.vo.LoginResultsVo;

/**
 * 教务系统服务接口
 * <p>
 * 从 UserContext 获取 userId 和 school cookies。
 */
public interface AcademicService {

    /**
     * 初始化教务系统会话
     * <p>
     * 通过重定向链获取教务系统 cookie，返回更新后的 cookies。
     */
    LoginResultsVo init();

    /**
     * 获取课程表
     */
    CourseTableVo getCrouseTable(CrouseTableQuery query);
}
