package com.smartpark.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartpark.common.DateTimes;
import com.smartpark.entity.AccessPermission;
import com.smartpark.entity.StockRecord;
import com.smartpark.entity.Visitor;
import com.smartpark.mapper.AccessPermissionMapper;
import com.smartpark.mapper.StockRecordMapper;
import com.smartpark.mapper.VisitorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时扫描任务：
 * ① 权限时效扫描：生效 → 临期（7 天内到期）→ 失效（批量条件更新，天然幂等）
 * ② 访客滞留扫描 / ③ 物资逾期扫描：循环在外，逐条独立小事务（见 {@link ScanTxHelper}），
 *    单条失败不影响本轮其余记录（try-catch 隔离），且因「先告警后改状态」可下一轮自动重试。
 */
@Slf4j
@Component
public class ScheduledTasks {

    private final AccessPermissionMapper permissionMapper;
    private final VisitorMapper visitorMapper;
    private final StockRecordMapper stockRecordMapper;
    private final ScanTxHelper scanTxHelper;

    public ScheduledTasks(AccessPermissionMapper permissionMapper,
                          VisitorMapper visitorMapper,
                          StockRecordMapper stockRecordMapper,
                          ScanTxHelper scanTxHelper) {
        this.permissionMapper = permissionMapper;
        this.visitorMapper = visitorMapper;
        this.stockRecordMapper = stockRecordMapper;
        this.scanTxHelper = scanTxHelper;
    }

    /** 权限时效：失效时间已过 → 失效；7 天内到期 → 临期 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scanPermissions() {
        String now = DateTimes.now();
        String sevenDaysLater = DateTimes.plusDays(7);

        // 生效 → 失效
        permissionMapper.update(null, new LambdaUpdateWrapper<AccessPermission>()
                .eq(AccessPermission::getStatus, "生效")
                .lt(AccessPermission::getEndTime, now)
                .set(AccessPermission::getStatus, "失效"));
        // 临期 → 失效
        permissionMapper.update(null, new LambdaUpdateWrapper<AccessPermission>()
                .eq(AccessPermission::getStatus, "临期")
                .lt(AccessPermission::getEndTime, now)
                .set(AccessPermission::getStatus, "失效"));
        // 生效 → 临期（未过期但 7 天内到期）
        permissionMapper.update(null, new LambdaUpdateWrapper<AccessPermission>()
                .eq(AccessPermission::getStatus, "生效")
                .ge(AccessPermission::getEndTime, now)
                .le(AccessPermission::getEndTime, sevenDaysLater)
                .set(AccessPermission::getStatus, "临期"));
    }

    /** 访客滞留：在场且当前时间 > expireTime → 滞留预警 + 告警 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scanVisitors() {
        String now = DateTimes.now();
        List<Visitor> overdue = visitorMapper.selectList(new LambdaQueryWrapper<Visitor>()
                .eq(Visitor::getStatus, "在场")
                .lt(Visitor::getExpireTime, now));
        for (Visitor visitor : overdue) {
            try {
                scanTxHelper.markVisitorOverdue(visitor.getId());
            } catch (Exception e) {
                // 单条失败隔离：不中断本轮剩余记录，日志留痕便于运维发现
                log.error("访客滞留扫描失败，visitorId={}", visitor.getId(), e);
            }
        }
    }

    /** 物资逾期：出库未还且 expectReturnTime 已过 → 物资置「逾期」+ 告警 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scanMaterials() {
        String now = DateTimes.now();
        List<StockRecord> overdue = stockRecordMapper.selectList(new LambdaQueryWrapper<StockRecord>()
                .eq(StockRecord::getType, "OUT")
                .in(StockRecord::getApproveStatus, "免审", "已批准")
                .isNull(StockRecord::getReturnTime)
                .lt(StockRecord::getExpectReturnTime, now));
        for (StockRecord record : overdue) {
            try {
                scanTxHelper.markMaterialOverdue(record.getId());
            } catch (Exception e) {
                log.error("物资逾期扫描失败，recordId={}", record.getId(), e);
            }
        }
    }
}
