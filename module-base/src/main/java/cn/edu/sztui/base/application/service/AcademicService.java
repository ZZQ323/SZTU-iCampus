package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.vo.CourseTableVO;
import cn.edu.sztui.base.application.vo.LoginResultsVo;

/**
 * 教务系统服务接口
 */
public interface AcademicService {
    
    /**
     * 初始化教务系统会话
     * <p>
     * 从 UserContext 获取 wxOpenId
     */
    LoginResultsVo init();
    
    /**
     * 获取课程表
     * <p>
     * 从 UserContext 获取 wxOpenId
     */
    CourseTableVO getCrouseTable(CrouseTableQuery query);
    
    /**
     * 【新增】获取课程表（直接传入 wxOpenId）
     * <p>
     * 用于异步场景（SSE 推送、定时任务等），无法使用 UserContext 的情况
     *
     * @param wxOpenId 微信 OpenId
     * @param query    查询参数
     * @return 课表数据
     */
    CourseTableVO getCrouseTableByOpenId(String wxOpenId, CrouseTableQuery query);
}
