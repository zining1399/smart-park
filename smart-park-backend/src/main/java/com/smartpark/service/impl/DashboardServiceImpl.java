package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartpark.common.DateTimes;
import com.smartpark.entity.AccessRecord;
import com.smartpark.entity.Alarm;
import com.smartpark.entity.Material;
import com.smartpark.entity.Visitor;
import com.smartpark.mapper.AccessRecordMapper;
import com.smartpark.mapper.AlarmMapper;
import com.smartpark.mapper.MaterialMapper;
import com.smartpark.mapper.VisitorMapper;
import com.smartpark.service.DashboardService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final AccessRecordMapper accessRecordMapper;
    private final VisitorMapper visitorMapper;
    private final MaterialMapper materialMapper;
    private final AlarmMapper alarmMapper;

    public DashboardServiceImpl(AccessRecordMapper accessRecordMapper,
                                VisitorMapper visitorMapper,
                                MaterialMapper materialMapper,
                                AlarmMapper alarmMapper) {
        this.accessRecordMapper = accessRecordMapper;
        this.visitorMapper = visitorMapper;
        this.materialMapper = materialMapper;
        this.alarmMapper = alarmMapper;
    }

    @Override
    public Map<String, Object> summary() {
        String today = DateTimes.today();

        Long todayAccessCount = accessRecordMapper.selectCount(new LambdaQueryWrapper<AccessRecord>()
                .ge(AccessRecord::getPassTime, today + " 00:00:00")
                .le(AccessRecord::getPassTime, today + " 23:59:59"));
        // 在场访客：在场 + 滞留预警（均尚未签离）
        Long visitorOnSite = visitorMapper.selectCount(new LambdaQueryWrapper<Visitor>()
                .in(Visitor::getStatus, "在场", "滞留预警"));
        Long materialOut = materialMapper.selectCount(new LambdaQueryWrapper<Material>()
                .eq(Material::getStatus, "在借"));
        Long materialOverdue = materialMapper.selectCount(new LambdaQueryWrapper<Material>()
                .eq(Material::getStatus, "逾期"));
        Long unhandledAlarm = alarmMapper.selectCount(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getStatus, "未处理"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todayAccessCount", todayAccessCount);
        result.put("visitorOnSite", visitorOnSite);
        result.put("materialOut", materialOut);
        result.put("materialOverdue", materialOverdue);
        result.put("unhandledAlarm", unhandledAlarm);
        return result;
    }

    @Override
    public Map<String, Object> accessTrend() {
        LocalDate today = LocalDate.now();
        LocalDate startDay = today.minusDays(6);
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MM-dd");

        String start = startDay.atStartOfDay().format(DateTimes.FMT);
        List<AccessRecord> records = accessRecordMapper.selectList(new LambdaQueryWrapper<AccessRecord>()
                .ge(AccessRecord::getPassTime, start));

        List<String> dates = new ArrayList<>();
        List<Long> inCounts = new ArrayList<>();
        List<Long> outCounts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = startDay.plusDays(i);
            String prefix = day.format(DateTimes.DAY);
            dates.add(day.format(labelFmt));
            inCounts.add(records.stream()
                    .filter(r -> r.getPassTime() != null && r.getPassTime().startsWith(prefix)
                            && "IN".equals(r.getDirection()))
                    .count());
            outCounts.add(records.stream()
                    .filter(r -> r.getPassTime() != null && r.getPassTime().startsWith(prefix)
                            && "OUT".equals(r.getDirection()))
                    .count());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("inCounts", inCounts);
        result.put("outCounts", outCounts);
        return result;
    }

    @Override
    public List<Map<String, Object>> materialStatus() {
        List<Material> materials = materialMapper.selectList(null);
        Map<String, Long> grouped = materials.stream()
                .collect(Collectors.groupingBy(m -> m.getStatus() == null ? "未知" : m.getStatus(),
                        LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((status, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", status);
            item.put("count", count);
            result.add(item);
        });
        return result;
    }
}
