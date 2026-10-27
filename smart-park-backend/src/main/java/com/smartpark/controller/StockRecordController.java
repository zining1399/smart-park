package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.ApproveRequest;
import com.smartpark.dto.StockInRequest;
import com.smartpark.dto.StockOutRequest;
import com.smartpark.entity.StockRecord;
import com.smartpark.service.StockRecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stock-records")
public class StockRecordController {

    private final StockRecordService stockRecordService;

    public StockRecordController(StockRecordService stockRecordService) {
        this.stockRecordService = stockRecordService;
    }

    @GetMapping
    public Result<PageResult<StockRecord>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "10") Integer pageSize,
                                                @RequestParam(required = false) String materialName,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String approveStatus,
                                                @RequestParam(required = false) String borrower) {
        Page<StockRecord> page = stockRecordService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize),
                materialName, type, approveStatus, borrower);
        return Result.ok(PageResult.of(page));
    }

    @PostMapping("/in")
    public Result<StockRecord> stockIn(@Valid @RequestBody StockInRequest request) {
        return Result.ok(stockRecordService.stockIn(request));
    }

    @PostMapping("/out")
    public Result<StockRecord> stockOut(@Valid @RequestBody StockOutRequest request) {
        return Result.ok(stockRecordService.stockOut(request));
    }

    @PostMapping("/{id}/approve")
    public Result<StockRecord> approve(@PathVariable Long id, @Valid @RequestBody ApproveRequest request) {
        return Result.ok(stockRecordService.approve(id, Boolean.TRUE.equals(request.getApproved())));
    }

    @PostMapping("/{id}/return")
    public Result<StockRecord> returnBack(@PathVariable Long id) {
        return Result.ok(stockRecordService.returnBack(id));
    }
}
