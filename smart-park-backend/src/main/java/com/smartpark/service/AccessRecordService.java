package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.dto.SimulateRequest;
import com.smartpark.entity.AccessRecord;

public interface AccessRecordService extends IService<AccessRecord> {

    Page<AccessRecord> pageQuery(int pageNum, int pageSize,
                                 String passType, String targetNo, String gate,
                                 String direction, String result,
                                 String startTime, String endTime);

    /**
     * 模拟终端上报：白名单/权限校验后放行或拦截（拦截写 reason 并产生告警）。
     */
    AccessRecord simulate(SimulateRequest request);
}
