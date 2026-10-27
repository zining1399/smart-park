package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.BizException;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.Employee;
import com.smartpark.mapper.EmployeeMapper;
import com.smartpark.security.crypto.SensitiveCrypto;
import com.smartpark.service.EmployeeService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.Serializable;

@Service
public class EmployeeServiceImpl extends ServiceImpl<EmployeeMapper, Employee> implements EmployeeService {

    private final SensitiveCrypto crypto;

    public EmployeeServiceImpl(SensitiveCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public Page<Employee> pageQuery(int pageNum, int pageSize, String name, String dept, String status) {
        LambdaQueryWrapper<Employee> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(name), Employee::getName, name)
                .eq(StringUtils.hasText(dept), Employee::getDept, dept)
                .eq(StringUtils.hasText(status), Employee::getStatus, status)
                .orderByDesc(Employee::getId);
        Page<Employee> page = page(new Page<>(PageSupport.clampPageNum(pageNum),
                PageSupport.clampPageSize(pageSize)), qw);
        page.getRecords().forEach(this::toMaskedView);
        return page;
    }

    @Override
    public Employee getById(Serializable id) {
        Employee employee = super.getById(id);
        return employee == null ? null : toMaskedView(employee);
    }

    @Override
    public Employee create(Employee employee) {
        if (!StringUtils.hasText(employee.getEmpNo()) || !StringUtils.hasText(employee.getName())) {
            throw new BizException("工号和姓名不能为空");
        }
        // S12：MySQL 默认大小写不敏感，业务唯一键统一大写归一化，避免 E1001 / e1001 被视为重复
        employee.setEmpNo(employee.getEmpNo().trim().toUpperCase());
        if (!StringUtils.hasText(employee.getStatus())) {
            employee.setStatus("在职");
        }
        if (count(new LambdaQueryWrapper<Employee>().eq(Employee::getEmpNo, employee.getEmpNo())) > 0) {
            throw BizException.conflict("工号已存在：" + employee.getEmpNo());
        }
        applyPhone(employee, employee.getPhone());
        save(employee);
        return toMaskedView(employee);
    }

    @Override
    public Employee update(Employee employee) {
        Employee exist = super.getById(employee.getId());
        if (exist == null) {
            throw BizException.notFound("员工不存在");
        }
        // S13：工号为业务唯一键，被通行权限/通行流水引用，禁止修改（改了会悬挂引用）
        employee.setEmpNo(exist.getEmpNo());
        String newPhone = SensitiveCrypto.looksMasked(employee.getPhone()) ? null : employee.getPhone();
        applyPhone(employee, newPhone);
        updateById(employee);
        return getById(employee.getId());
    }

    @Override
    public void delete(Long id) {
        if (super.getById(id) == null) {
            throw BizException.notFound("员工不存在");
        }
        removeById(id);
    }

    /** 手机号：写入密文 + hash + mask；plain 为 null 表示不更新 */
    private void applyPhone(Employee employee, String plainPhone) {
        if (plainPhone == null) {
            employee.setPhone(null);
            employee.setPhoneHash(null);
            employee.setPhoneMask(null);
            return;
        }
        employee.setPhone(crypto.encrypt(plainPhone));
        employee.setPhoneHash(crypto.hash(plainPhone));
        employee.setPhoneMask(crypto.maskPhone(plainPhone));
    }

    private Employee toMaskedView(Employee e) {
        e.setPhone(e.getPhoneMask());
        e.setPhoneHash(null);
        return e;
    }
}
