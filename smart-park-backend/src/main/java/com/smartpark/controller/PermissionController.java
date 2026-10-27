package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.AccessPermissionCreateRequest;
import com.smartpark.dto.AccessPermissionUpdateRequest;
import com.smartpark.entity.AccessPermission;
import com.smartpark.service.AccessPermissionService;
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
@RequestMapping("/permissions")
public class PermissionController {

    private final AccessPermissionService permissionService;

    public PermissionController(AccessPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public Result<PageResult<AccessPermission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                     @RequestParam(defaultValue = "10") Integer pageSize,
                                                     @RequestParam(required = false) String targetType,
                                                     @RequestParam(required = false) String targetNo,
                                                     @RequestParam(required = false) String area,
                                                     @RequestParam(required = false) String status) {
        Page<AccessPermission> page = permissionService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize),
                targetType, targetNo, area, status);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<AccessPermission> detail(@PathVariable Long id) {
        return Result.ok(permissionService.getById(id));
    }

    @PostMapping
    public Result<AccessPermission> create(@Valid @RequestBody AccessPermissionCreateRequest request) {
        AccessPermission permission = new AccessPermission();
        permission.setTargetType(request.getTargetType());
        permission.setTargetNo(request.getTargetNo());
        permission.setTargetName(request.getTargetName());
        permission.setArea(request.getArea());
        permission.setStartTime(request.getStartTime());
        permission.setEndTime(request.getEndTime());
        return Result.ok(permissionService.create(permission));
    }

    @PutMapping("/{id}")
    public Result<AccessPermission> update(@PathVariable Long id,
                                           @Valid @RequestBody AccessPermissionUpdateRequest request) {
        AccessPermission permission = new AccessPermission();
        permission.setId(id);
        permission.setTargetType(request.getTargetType());
        permission.setTargetNo(request.getTargetNo());
        permission.setTargetName(request.getTargetName());
        permission.setArea(request.getArea());
        permission.setStartTime(request.getStartTime());
        permission.setEndTime(request.getEndTime());
        return Result.ok(permissionService.update(permission));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.ok();
    }
}
