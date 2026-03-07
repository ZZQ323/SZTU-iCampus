package cn.edu.sztui.base.web;

import cn.edu.sztui.base.application.dto.query.CrouseTableQuery;
import cn.edu.sztui.common.util.result.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/acdm")
public class AcademicController {


    @GetMapping("/v1/refresh/cookies")
    public Result refreshCookies() {
        return Result.ok();
    }

    /**
     * 获取课表，直接返回课表table
     * @param
     * @return
     */
    @PostMapping("/v1/schedule")
    public Result getCrousetable(@RequestBody CrouseTableQuery query) {
        return Result.ok();
    }
}
