package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.base.application.service.AcademicService;
import cn.edu.sztui.common.util.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/acdmadminsys")
public class AcademicController {

    @Autowired
    private AcademicService academicService;

    /**
     * 获取课表，直接返回课表table
     *
     * @param
     * @return
     */
    @PostMapping("/v1/schedule")
    public Result getCrousetable(@RequestBody CrouseTableQuery query) {
        return Result.ok(academicService.getCrouseTable(query));
    }
}
