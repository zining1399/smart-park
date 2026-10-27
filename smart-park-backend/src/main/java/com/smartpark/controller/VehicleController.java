package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.VehicleCreateRequest;
import com.smartpark.dto.VehicleUpdateRequest;
import com.smartpark.entity.Vehicle;
import com.smartpark.service.VehicleService;
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
@RequestMapping("/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public Result<PageResult<Vehicle>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                            @RequestParam(required = false) String plateNo,
                                            @RequestParam(required = false) String ownerType,
                                            @RequestParam(required = false) String status) {
        Page<Vehicle> page = vehicleService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize), plateNo, ownerType, status);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<Vehicle> detail(@PathVariable Long id) {
        return Result.ok(vehicleService.getById(id));
    }

    @PostMapping
    public Result<Vehicle> create(@Valid @RequestBody VehicleCreateRequest request) {
        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(request.getPlateNo());
        vehicle.setOwnerType(request.getOwnerType());
        vehicle.setOwnerName(request.getOwnerName());
        vehicle.setDept(request.getDept());
        vehicle.setStatus(request.getStatus());
        return Result.ok(vehicleService.create(vehicle));
    }

    /** 车牌号为业务唯一键，不可修改（被通行权限与流水引用） */
    @PutMapping("/{id}")
    public Result<Vehicle> update(@PathVariable Long id, @Valid @RequestBody VehicleUpdateRequest request) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setOwnerType(request.getOwnerType());
        vehicle.setOwnerName(request.getOwnerName());
        vehicle.setDept(request.getDept());
        vehicle.setStatus(request.getStatus());
        return Result.ok(vehicleService.update(vehicle));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return Result.ok();
    }
}
