package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.Vehicle;
import com.smartpark.mapper.VehicleMapper;
import com.smartpark.service.VehicleService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VehicleServiceImpl extends ServiceImpl<VehicleMapper, Vehicle> implements VehicleService {

    private final AuditLogger auditLogger;

    public VehicleServiceImpl(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<Vehicle> pageQuery(int pageNum, int pageSize, String plateNo, String ownerType, String status) {
        LambdaQueryWrapper<Vehicle> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(plateNo), Vehicle::getPlateNo, plateNo)
                .eq(StringUtils.hasText(ownerType), Vehicle::getOwnerType, ownerType)
                .eq(StringUtils.hasText(status), Vehicle::getStatus, status)
                .orderByDesc(Vehicle::getId);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    public Vehicle create(Vehicle vehicle) {
        if (!StringUtils.hasText(vehicle.getPlateNo())) {
            throw new BizException("车牌号不能为空");
        }
        // S12：业务唯一键统一大写归一化（MySQL 大小写不敏感，避免 新A12345 / 新a12345 被判重复）
        vehicle.setPlateNo(vehicle.getPlateNo().trim().toUpperCase());
        if (!StringUtils.hasText(vehicle.getStatus())) {
            vehicle.setStatus("正常");
        }
        Long count = count(new LambdaQueryWrapper<Vehicle>().eq(Vehicle::getPlateNo, vehicle.getPlateNo()));
        if (count > 0) {
            throw BizException.conflict("车牌号已存在：" + vehicle.getPlateNo());
        }
        save(vehicle);
        return vehicle;
    }

    @Override
    public Vehicle update(Vehicle vehicle) {
        Vehicle exist = getById(vehicle.getId());
        if (exist == null) {
            throw BizException.notFound("车辆不存在");
        }
        // S13：车牌号被通行权限/通行流水引用，禁止修改（改了会悬挂引用）
        vehicle.setPlateNo(exist.getPlateNo());
        updateById(vehicle);
        return getById(vehicle.getId());
    }

    @Override
    public void delete(Long id) {
        Vehicle exist = getById(id);
        if (exist == null) {
            throw BizException.notFound("车辆不存在");
        }
        removeById(id);
        auditLogger.audit("DELETE_VEHICLE", exist.getPlateNo(), "SUCCESS", "id=" + id);
    }
}
