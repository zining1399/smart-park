package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.SimulateRequest;
import com.smartpark.entity.AccessRecord;
import com.smartpark.service.AccessRecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/access-records")
public class AccessRecordController {

    private final AccessRecordService accessRecordService;

    public AccessRecordController(AccessRecordService accessRecordService) {
        this.accessRecordService = accessRecordService;
    }

    @GetMapping
    public Result<PageResult<AccessRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                 @RequestParam(defaultValue = "10") Integer pageSize,
                                                 @RequestParam(required = false) String passType,
                                                 @RequestParam(required = false) String targetNo,
                                                 @RequestParam(required = false) String gate,
                                                 @RequestParam(required = false) String direction,
                                                 @RequestParam(required = false) String result,
                                                 @RequestParam(required = false) String startTime,
                                                 @RequestParam(required = false) String endTime) {
        Page<AccessRecord> page = accessRecordService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize),
                passType, targetNo, gate, direction, result, startTime, endTime);
        return Result.ok(PageResult.of(page));
    }

    /** 模拟终端上报（演示/联调用，生产受限门岗与管理员） */
    @PostMapping("/simulate")
    public Result<AccessRecord> simulate(@Valid @RequestBody SimulateRequest request) {
        return Result.ok(accessRecordService.simulate(request));
    }
}
