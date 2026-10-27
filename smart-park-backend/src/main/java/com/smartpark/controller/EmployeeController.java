package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.EmployeeCreateRequest;
import com.smartpark.dto.EmployeeUpdateRequest;
import com.smartpark.entity.Employee;
import com.smartpark.service.EmployeeService;
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
@RequestMapping("/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public Result<PageResult<Employee>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String dept,
                                             @RequestParam(required = false) String status) {
        Page<Employee> page = employeeService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize), name, dept, status);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<Employee> detail(@PathVariable Long id) {
        return Result.ok(employeeService.getById(id));
    }

    @PostMapping
    public Result<Employee> create(@Valid @RequestBody EmployeeCreateRequest request) {
        Employee employee = new Employee();
        employee.setEmpNo(request.getEmpNo());
        employee.setName(request.getName());
        employee.setDept(request.getDept());
        employee.setPhone(request.getPhone());
        employee.setStatus(request.getStatus());
        return Result.ok(employeeService.create(employee));
    }

    /** 工号为业务唯一键，不可修改（入参不含 empNo） */
    @PutMapping("/{id}")
    public Result<Employee> update(@PathVariable Long id, @Valid @RequestBody EmployeeUpdateRequest request) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(request.getName());
        employee.setDept(request.getDept());
        employee.setPhone(request.getPhone());
        employee.setStatus(request.getStatus());
        return Result.ok(employeeService.update(employee));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        employeeService.delete(id);
        return Result.ok();
    }
}
