package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.entity.Alarm;
import com.smartpark.service.AlarmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @GetMapping
    public Result<PageResult<Alarm>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String level,
                                          @RequestParam(required = false) String status) {
        Page<Alarm> page = alarmService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize), type, level, status);
        return Result.ok(PageResult.of(page));
    }

    @PostMapping("/{id}/handle")
    public Result<Alarm> handle(@PathVariable Long id) {
        return Result.ok(alarmService.handle(id));
    }
}
