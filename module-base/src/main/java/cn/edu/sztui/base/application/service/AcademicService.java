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
     * HTTP 入口：从 UserContext 取 cookies，成功后发布 AcademicSessionReadyEvent。
     */
    LoginResultsVo init();

    /**
     * 初始化教务系统会话（内部调用版）
     * <p>
     * 不依赖 UserContext，不发布事件。用于爬虫自愈（session 过期时就地续命），
     * 调用方自行决定是否 / 何时发事件。返回更新后的 cookies JSON（null = 失败）。
     */
    String initInternal(String userId, String cookiesJson);

    /**
     * 获取课程表
     */
    CourseTableVo getCrouseTable(CrouseTableQuery query);
}
