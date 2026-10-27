package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.Vehicle;

public interface VehicleService extends IService<Vehicle> {

    Page<Vehicle> pageQuery(int pageNum, int pageSize, String plateNo, String ownerType, String status);

    Vehicle create(Vehicle vehicle);

    Vehicle update(Vehicle vehicle);

    void delete(Long id);
}
