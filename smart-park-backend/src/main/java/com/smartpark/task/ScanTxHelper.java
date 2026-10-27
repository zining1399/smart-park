package com.smartpark.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartpark.common.DateTimes;
import com.smartpark.entity.Material;
import com.smartpark.entity.StockRecord;
import com.smartpark.entity.Visitor;
import com.smartpark.mapper.MaterialMapper;
import com.smartpark.mapper.StockRecordMapper;
import com.smartpark.mapper.VisitorMapper;
import com.smartpark.service.AlarmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时扫描的逐条事务载体（裁决 13）：
 * 每条记录一个独立小事务（REQUIRES_NEW），必须抽到独立 Bean——同类内 this 调用注解不生效。
 * 事务内顺序固定为「先建告警、后改状态」：告警写失败则状态不变，下一轮扫描守卫重新命中，告警不会静默丢失。
 */
@Slf4j
@Component
public class ScanTxHelper {

    private final VisitorMapper visitorMapper;
    private final StockRecordMapper stockRecordMapper;
    private final MaterialMapper materialMapper;
    private final AlarmService alarmService;

    public ScanTxHelper(VisitorMapper visitorMapper,
                        StockRecordMapper stockRecordMapper,
                        MaterialMapper materialMapper,
                        AlarmService alarmService) {
        this.visitorMapper = visitorMapper;
        this.stockRecordMapper = stockRecordMapper;
        this.materialMapper = materialMapper;
        this.alarmService = alarmService;
    }

    /** 访客滞留：在场且已过 expireTime → 先告警后置「滞留预警」 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markVisitorOverdue(Long visitorId) {
        Visitor visitor = visitorMapper.selectById(visitorId);
        if (visitor == null || !"在场".equals(visitor.getStatus())) {
            return;
        }
        alarmService.createAlarm("访客滞留",
                "访客" + visitor.getVisitorName() + "滞留预警",
                "访客" + visitor.getVisitorName() + "授权已于 " + visitor.getExpireTime()
                        + " 失效，仍未签离（被访人：" + visitor.getHostName() + "）。",
                "警告");
        int rows = visitorMapper.update(null, new LambdaUpdateWrapper<Visitor>()
                .eq(Visitor::getId, visitorId)
                .eq(Visitor::getStatus, "在场")
                .set(Visitor::getStatus, "滞留预警"));
        if (rows != 1) {
            log.warn("访客滞留状态已变更，跳过：visitorId={}", visitorId);
            return;
        }
        log.info("访客滞留预警：{}", visitor.getVisitorName());
    }

    /** 物资逾期：出库未还且已过 expectReturnTime → 先告警后置「逾期」 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markMaterialOverdue(Long recordId) {
        StockRecord record = stockRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        Material material = materialMapper.selectOne(new LambdaQueryWrapper<Material>()
                .eq(Material::getCode, record.getMaterialCode()));
        if (material == null || !"在借".equals(material.getStatus())) {
            return;
        }
        alarmService.createAlarm("物资逾期",
                material.getName() + "逾期未还",
                "物资 " + material.getCode() + "（" + material.getName() + "）领用人"
                        + record.getBorrower() + "，预计归还 " + record.getExpectReturnTime() + "，已逾期。",
                "警告");
        // 条件更新：仅当仍为「在借」才置逾期，避免与并发归还冲突
        int rows = materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                .eq(Material::getId, material.getId())
                .eq(Material::getStatus, "在借")
                .set(Material::getStatus, "逾期"));
        if (rows != 1) {
            log.warn("物资状态已变更，跳过逾期标记：materialCode={}", material.getCode());
            return;
        }
        log.info("物资逾期告警：{}（recordId={}）", material.getCode(), recordId);
    }

    /** 当前时间（与业务时间字符串格式一致） */
    public String now() {
        return DateTimes.now();
    }
}
