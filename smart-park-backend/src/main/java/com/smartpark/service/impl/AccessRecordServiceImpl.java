package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.BizException;
import com.smartpark.common.DateTimes;
import com.smartpark.common.PageSupport;
import com.smartpark.dto.SimulateRequest;
import com.smartpark.entity.AccessPermission;
import com.smartpark.entity.AccessRecord;
import com.smartpark.entity.Employee;
import com.smartpark.entity.Vehicle;
import com.smartpark.mapper.AccessPermissionMapper;
import com.smartpark.mapper.AccessRecordMapper;
import com.smartpark.mapper.EmployeeMapper;
import com.smartpark.mapper.VehicleMapper;
import com.smartpark.service.AccessRecordService;
import com.smartpark.service.AlarmService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

@Service
public class AccessRecordServiceImpl extends ServiceImpl<AccessRecordMapper, AccessRecord>
        implements AccessRecordService {

    /** 门岗 → 区域 映射（契约未定义，作为实现假设） */
    private static final Map<String, String> GATE_AREA = Map.of(
            "东门", "办公区",
            "南门", "生产区",
            "西门", "生活区",
            "货运门", "仓储区"
    );

    private final VehicleMapper vehicleMapper;
    private final EmployeeMapper employeeMapper;
    private final AccessPermissionMapper permissionMapper;
    private final AlarmService alarmService;
    private final AuditLogger auditLogger;

    public AccessRecordServiceImpl(VehicleMapper vehicleMapper,
                                   EmployeeMapper employeeMapper,
                                   AccessPermissionMapper permissionMapper,
                                   @Lazy AlarmService alarmService,
                                   AuditLogger auditLogger) {
        this.vehicleMapper = vehicleMapper;
        this.employeeMapper = employeeMapper;
        this.permissionMapper = permissionMapper;
        this.alarmService = alarmService;
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<AccessRecord> pageQuery(int pageNum, int pageSize,
                                        String passType, String targetNo, String gate,
                                        String direction, String result,
                                        String startTime, String endTime) {
        LambdaQueryWrapper<AccessRecord> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(passType), AccessRecord::getPassType, passType)
                .like(StringUtils.hasText(targetNo), AccessRecord::getTargetNo, targetNo)
                .eq(StringUtils.hasText(gate), AccessRecord::getGate, gate)
                .eq(StringUtils.hasText(direction), AccessRecord::getDirection, direction)
                .eq(StringUtils.hasText(result), AccessRecord::getResult, result)
                .ge(StringUtils.hasText(startTime), AccessRecord::getPassTime, startTime)
                .le(StringUtils.hasText(endTime), AccessRecord::getPassTime, endTime)
                .orderByDesc(AccessRecord::getPassTime);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    public AccessRecord simulate(SimulateRequest request) {
        String area = GATE_AREA.get(request.getGate());
        if (area == null) {
            throw new BizException("未知门岗：" + request.getGate());
        }

        AccessRecord record = new AccessRecord();
        record.setPassType(request.getPassType());
        record.setTargetNo(request.getTargetNo());
        record.setGate(request.getGate());
        record.setDirection(request.getDirection());
        record.setPassTime(DateTimes.now());

        if ("VEHICLE".equals(request.getPassType())) {
            checkVehicle(record, area);
        } else {
            checkPerson(record, area);
        }

        save(record);
        // 门禁放行/拦截是等保重点留痕项：必带操作人与 traceId
        auditLogger.audit("ACCESS_SIMULATE", record.getTargetNo(), record.getResult(),
                record.getPassType() + "/" + record.getGate() + "/" + record.getDirection()
                        + (record.getReason() == null ? "" : " reason=" + record.getReason()));
        return record;
    }

    private void checkVehicle(AccessRecord record, String area) {
        String plateNo = record.getTargetNo();
        record.setTargetName(plateNo);
        Vehicle vehicle = vehicleMapper.selectOne(
                new LambdaQueryWrapper<Vehicle>().eq(Vehicle::getPlateNo, plateNo));
        if (vehicle == null) {
            deny(record, "车辆未登记",
                    "门禁异常", "未登记车辆试图通行", "车辆" + plateNo + "未登记，于" + record.getGate() + "被拦截。", "警告");
            return;
        }
        if ("黑名单".equals(vehicle.getStatus())) {
            deny(record, "车辆处于黑名单",
                    "黑名单", "黑名单车辆试图通行", "车辆" + plateNo + "处于黑名单，于" + record.getGate() + "被拦截。", "严重");
            return;
        }
        if (!hasPermission("VEHICLE", plateNo, area)) {
            deny(record, "无" + area + "有效通行权限",
                    "门禁异常", "车辆无权限通行", "车辆" + plateNo + "无" + area + "有效通行权限，于" + record.getGate() + "被拦截。", "警告");
            return;
        }
        record.setResult("放行");
    }

    private void checkPerson(AccessRecord record, String area) {
        String empNo = record.getTargetNo();
        record.setTargetName(empNo);
        Employee employee = employeeMapper.selectOne(
                new LambdaQueryWrapper<Employee>().eq(Employee::getEmpNo, empNo));
        if (employee == null) {
            deny(record, "工号未登记",
                    "门禁异常", "未登记人员试图通行", "工号" + empNo + "未登记，于" + record.getGate() + "被拦截。", "警告");
            return;
        }
        record.setTargetName(employee.getName());
        if (!"在职".equals(employee.getStatus())) {
            deny(record, "员工已离职",
                    "门禁异常", "离职员工刷卡拦截", "员工" + employee.getName() + "（" + empNo + "）已离职，于" + record.getGate() + "被拦截。", "提示");
            return;
        }
        if (!hasPermission("PERSON", empNo, area)) {
            deny(record, "无" + area + "有效通行权限",
                    "门禁异常", "无权限人员试图通行", "员工" + employee.getName() + "（" + empNo + "）无" + area + "有效通行权限，于" + record.getGate() + "被拦截。", "警告");
            return;
        }
        record.setResult("放行");
    }

    private boolean hasPermission(String targetType, String targetNo, String area) {
        String now = DateTimes.now();
        Long count = permissionMapper.selectCount(new LambdaQueryWrapper<AccessPermission>()
                .eq(AccessPermission::getTargetType, targetType)
                .eq(AccessPermission::getTargetNo, targetNo)
                .eq(AccessPermission::getArea, area)
                .le(AccessPermission::getStartTime, now)
                .ge(AccessPermission::getEndTime, now));
        return count != null && count > 0;
    }

    private void deny(AccessRecord record, String reason,
                      String alarmType, String alarmTitle, String alarmContent, String alarmLevel) {
        record.setResult("拦截");
        record.setReason(reason);
        alarmService.createAlarm(alarmType, alarmTitle, alarmContent, alarmLevel);
    }
}
