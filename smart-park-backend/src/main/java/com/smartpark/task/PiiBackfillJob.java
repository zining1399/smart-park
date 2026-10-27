package com.smartpark.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartpark.entity.Employee;
import com.smartpark.entity.Visitor;
import com.smartpark.mapper.EmployeeMapper;
import com.smartpark.mapper.VisitorMapper;
import com.smartpark.security.crypto.SensitiveCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次性 PII 加密回填 Job（条件交付：仅当客户现场存在 H2 存量真实数据时启用）。
 * 启用方式：设置环境变量 SMARTPARK_PII_BACKFILL=true 后启动一次，完成后关闭。
 * <p>
 * 红线：加解密只走 Java 层 {@link SensitiveCrypto}（与在线写路径同一套密钥与算法），
 * 禁止在 SQL / shell / python 脚本中实现——密钥会落进脚本、shell history 与慢日志。
 * <p>
 * 幂等：已处理行的 *_hash 列非空，下次运行直接跳过。
 * <p>
 * 风险：被跳过的行，明文仍然留在密文列里（半明半密、外表看不出来），因此跳过时必须打印 id 清单
 * 而不只是计数；且本 Job 是一次全表批量 UPDATE，写错即全库密文损坏且不可逆——
 * 运行前必须已完成全量备份（见 Job 运行手册）。
 */
@Slf4j
@Component
@Order(2)
@ConditionalOnProperty(prefix = "smartpark.pii-backfill", name = "enabled", havingValue = "true")
public class PiiBackfillJob implements CommandLineRunner {

    /** 身份证明文：18 位，末位可为 X */
    private static final String ID_CARD_PLAIN = "^\\d{17}[0-9Xx]$";
    /** 手机号明文：1 开头 11 位 */
    private static final String PHONE_PLAIN = "^1\\d{10}$";

    private final VisitorMapper visitorMapper;
    private final EmployeeMapper employeeMapper;
    private final SensitiveCrypto crypto;

    public PiiBackfillJob(VisitorMapper visitorMapper, EmployeeMapper employeeMapper, SensitiveCrypto crypto) {
        this.visitorMapper = visitorMapper;
        this.employeeMapper = employeeMapper;
        this.crypto = crypto;
    }

    @Override
    public void run(String... args) {
        log.warn("==================================================================");
        log.warn("PII 加密回填 Job 启动：这是一次性全表批量 UPDATE，会覆盖 id_card / phone 列原值。");
        log.warn("前置条件：执行前必须已完成全量备份。密文写错后原明文不可还原，事故不可逆。");
        log.warn("处理完成后请立即关闭 smartpark.pii-backfill.enabled（去掉环境变量或置 false）。");
        log.warn("==================================================================");
        int[] visitorStats = backfillVisitors();
        int[] employeeStats = backfillEmployees();
        log.warn("PII 加密回填完成：访客处理 {} 条 / 跳过 {} 条；员工处理 {} 条 / 跳过 {} 条",
                visitorStats[0], visitorStats[1], employeeStats[0], employeeStats[1]);
        if (visitorStats[1] + employeeStats[1] > 0) {
            log.error("存在被跳过的行：这些行的明文仍留在密文列中（库内半明半密，外表不可见），"
                    + "必须人工核对后再决定是否二次处理。跳过 id 清单见上方 WARN 日志。");
        }
    }

    private int[] backfillVisitors() {
        int handled = 0;
        int skipped = 0;
        List<Long> skippedIds = new ArrayList<>();
        List<Visitor> list = visitorMapper.selectList(new LambdaQueryWrapper<Visitor>()
                .isNull(Visitor::getIdCardHash)
                .or()
                .isNull(Visitor::getPhoneHash));
        for (Visitor v : list) {
            boolean changed = false;
            if (v.getIdCard() != null && v.getIdCard().matches(ID_CARD_PLAIN)) {
                v.setIdCardHash(crypto.hash(v.getIdCard()));
                v.setIdCardMask(crypto.maskIdCard(v.getIdCard()));
                v.setIdCard(crypto.encrypt(v.getIdCard()));
                changed = true;
            }
            if (v.getPhone() != null && v.getPhone().matches(PHONE_PLAIN)) {
                v.setPhoneHash(crypto.hash(v.getPhone()));
                v.setPhoneMask(crypto.maskPhone(v.getPhone()));
                v.setPhone(crypto.encrypt(v.getPhone()));
                changed = true;
            }
            if (changed) {
                visitorMapper.updateById(v);
                handled++;
            } else {
                skipped++;
                skippedIds.add(v.getId());
            }
        }
        if (!skippedIds.isEmpty()) {
            log.warn("访客回填跳过 {} 条（明文格式不匹配，疑似已是密文）：skippedVisitorIds={}",
                    skippedIds.size(), skippedIds);
        }
        return new int[]{handled, skipped};
    }

    private int[] backfillEmployees() {
        int handled = 0;
        int skipped = 0;
        List<Long> skippedIds = new ArrayList<>();
        List<Employee> list = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .isNull(Employee::getPhoneHash));
        for (Employee e : list) {
            if (e.getPhone() != null && e.getPhone().matches(PHONE_PLAIN)) {
                e.setPhoneHash(crypto.hash(e.getPhone()));
                e.setPhoneMask(crypto.maskPhone(e.getPhone()));
                e.setPhone(crypto.encrypt(e.getPhone()));
                employeeMapper.updateById(e);
                handled++;
            } else {
                skipped++;
                skippedIds.add(e.getId());
            }
        }
        if (!skippedIds.isEmpty()) {
            log.warn("员工回填跳过 {} 条（明文格式不匹配，疑似已是密文）：skippedEmployeeIds={}",
                    skippedIds.size(), skippedIds);
        }
        return new int[]{handled, skipped};
    }
}
