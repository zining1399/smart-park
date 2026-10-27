package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.Alarm;

public interface AlarmService extends IService<Alarm> {

    Page<Alarm> pageQuery(int pageNum, int pageSize, String type, String level, String status);

    /** 处理告警：置「已处理」并写 handleTime */
    Alarm handle(Long id);

    /** 供其他业务模块产生告警 */
    Alarm createAlarm(String type, String title, String content, String level);
}
