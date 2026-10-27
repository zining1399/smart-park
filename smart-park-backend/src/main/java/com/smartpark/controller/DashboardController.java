package com.smartpark.controller;

import com.smartpark.common.Result;
import com.smartpark.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(dashboardService.summary());
    }

    @GetMapping("/access-trend")
    public Result<Map<String, Object>> accessTrend() {
        return Result.ok(dashboardService.accessTrend());
    }

    @GetMapping("/material-status")
    public Result<List<Map<String, Object>>> materialStatus() {
        return Result.ok(dashboardService.materialStatus());
    }
}
