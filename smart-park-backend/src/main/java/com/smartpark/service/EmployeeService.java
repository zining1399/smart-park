package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.Employee;

public interface EmployeeService extends IService<Employee> {

    Page<Employee> pageQuery(int pageNum, int pageSize, String name, String dept, String status);

    Employee create(Employee employee);

    Employee update(Employee employee);

    void delete(Long id);
}
