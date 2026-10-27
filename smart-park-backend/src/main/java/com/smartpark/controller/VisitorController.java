package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.VisitorCreateRequest;
import com.smartpark.dto.VisitorUpdateRequest;
import com.smartpark.entity.Visitor;
import com.smartpark.service.VisitorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/visitors")
public class VisitorController {

    private final VisitorService visitorService;

    public VisitorController(VisitorService visitorService) {
        this.visitorService = visitorService;
    }

    @GetMapping
    public Result<PageResult<Visitor>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                            @RequestParam(required = false) String visitorName,
                                            @RequestParam(required = false) String hostName,
                                            @RequestParam(required = false) String status) {
        Page<Visitor> page = visitorService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize),
                visitorName, hostName, status);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<Visitor> detail(@PathVariable Long id) {
        return Result.ok(visitorService.getMasked(id));
    }

    @PostMapping
    public Result<Visitor> create(@Valid @RequestBody VisitorCreateRequest request) {
        Visitor visitor = new Visitor();
        visitor.setVisitorName(request.getVisitorName());
        visitor.setIdCard(request.getIdCard());
        visitor.setPhone(request.getPhone());
        visitor.setPlateNo(request.getPlateNo());
        visitor.setHostName(request.getHostName());
        visitor.setHostDept(request.getHostDept());
        visitor.setReason(request.getReason());
        visitor.setArea(request.getArea());
        visitor.setAppointTime(request.getAppointTime());
        visitor.setExpireTime(request.getExpireTime());
        return Result.ok(visitorService.create(visitor));
    }

    /** 状态机字段（status / checkInTime / checkOutTime）不接收外部传入 */
    @PutMapping("/{id}")
    public Result<Visitor> update(@PathVariable Long id, @Valid @RequestBody VisitorUpdateRequest request) {
        Visitor visitor = new Visitor();
        visitor.setId(id);
        visitor.setVisitorName(request.getVisitorName());
        visitor.setIdCard(request.getIdCard());
        visitor.setPhone(request.getPhone());
        visitor.setPlateNo(request.getPlateNo());
        visitor.setHostName(request.getHostName());
        visitor.setHostDept(request.getHostDept());
        visitor.setReason(request.getReason());
        visitor.setArea(request.getArea());
        visitor.setAppointTime(request.getAppointTime());
        visitor.setExpireTime(request.getExpireTime());
        return Result.ok(visitorService.update(visitor));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        visitorService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/check-in")
    public Result<Visitor> checkIn(@PathVariable Long id) {
        return Result.ok(visitorService.checkIn(id));
    }

    @PostMapping("/{id}/check-out")
    public Result<Visitor> checkOut(@PathVariable Long id) {
        return Result.ok(visitorService.checkOut(id));
    }
}
