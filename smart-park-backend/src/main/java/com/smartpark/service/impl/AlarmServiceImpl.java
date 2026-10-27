package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.DateTimes;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.Alarm;
import com.smartpark.mapper.AlarmMapper;
import com.smartpark.service.AlarmService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AlarmServiceImpl extends ServiceImpl<AlarmMapper, Alarm> implements AlarmService {

    private final AuditLogger auditLogger;

    public AlarmServiceImpl(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<Alarm> pageQuery(int pageNum, int pageSize, String type, String level, String status) {
        LambdaQueryWrapper<Alarm> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(type), Alarm::getType, type)
                .eq(StringUtils.hasText(level), Alarm::getLevel, level)
                .eq(StringUtils.hasText(status), Alarm::getStatus, status)
                .orderByDesc(Alarm::getId);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    public Alarm handle(Long id) {
        Alarm alarm = getById(id);
        if (alarm == null) {
            throw BizException.notFound("告警不存在");
        }
        // 条件更新：未处理 → 已处理（防并发重复处理）
        int rows = baseMapper.update(null, new LambdaUpdateWrapper<Alarm>()
                .eq(Alarm::getId, id)
                .eq(Alarm::getStatus, "未处理")
                .set(Alarm::getStatus, "已处理")
                .set(Alarm::getHandleTime, DateTimes.now()));
        if (rows != 1) {
            throw BizException.conflict("该告警已处理，请勿重复操作");
        }
        alarm.setStatus("已处理");
        alarm.setHandleTime(DateTimes.now());
        auditLogger.audit("ALARM_HANDLE", String.valueOf(id), "SUCCESS",
                "type=" + alarm.getType() + " level=" + alarm.getLevel());
        return alarm;
    }

    @Override
    public Alarm createAlarm(String type, String title, String content, String level) {
        Alarm alarm = new Alarm();
        alarm.setType(type);
        alarm.setTitle(title);
        alarm.setContent(content);
        alarm.setLevel(StringUtils.hasText(level) ? level : "警告");
        alarm.setStatus("未处理");
        alarm.setCreateTime(DateTimes.now());
        save(alarm);
        return alarm;
    }
}
