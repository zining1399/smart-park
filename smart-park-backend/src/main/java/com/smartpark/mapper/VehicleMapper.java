package com.smartpark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartpark.entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VehicleMapper extends BaseMapper<Vehicle> {
}
