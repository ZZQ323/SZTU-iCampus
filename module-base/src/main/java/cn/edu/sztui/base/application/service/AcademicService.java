package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.vo.CourseTableVO;
import cn.edu.sztui.base.application.vo.LoginResultsVo;

public interface AcademicService {
    LoginResultsVo init();
    CourseTableVO getCrouseTable(CrouseTableQuery query);
}
