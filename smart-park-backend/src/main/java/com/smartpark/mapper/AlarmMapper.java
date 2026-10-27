package com.smartpark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartpark.entity.Alarm;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlarmMapper extends BaseMapper<Alarm> {
}
