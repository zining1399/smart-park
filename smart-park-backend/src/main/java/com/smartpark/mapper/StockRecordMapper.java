package com.smartpark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartpark.entity.StockRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockRecordMapper extends BaseMapper<StockRecord> {
}
