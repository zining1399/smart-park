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

import java.util.List;

/**
 * 一次性 PII 加密回填 Job（条件交付：仅当客户现场存在 H2 存量真实数据时启用）。
 * 启用方式：设置环境变量 SMARTPARK_PII_BACKFILL=true 后启动一次，完成后关闭。
 * <p>
 * 红线：加解密只走 Java 层 {@link SensitiveCrypto}（与在线写路径同一套密钥与算法），
 * 禁止在 SQL / shell / python 脚本中实现——密钥会落进脚本、shell history 与慢日志。
 * <p>
 * 幂等：已处理行的 *_hash 列非空，下次运行直接跳过；明文格式不匹配的行（疑似已是密文）跳过并计数。
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
        log.warn("PII 加密回填 Job 启动（一次性任务，处理完成后请关闭 smartpark.pii-backfill.enabled）");
        int[] visitorStats = backfillVisitors();
        int[] employeeStats = backfillEmployees();
        log.warn("PII 加密回填完成：访客处理 {} 条 / 跳过 {} 条；员工处理 {} 条 / 跳过 {} 条",
                visitorStats[0], visitorStats[1], employeeStats[0], employeeStats[1]);
    }

    private int[] backfillVisitors() {
        int handled = 0;
        int skipped = 0;
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
            }
        }
        return new int[]{handled, skipped};
    }

    private int[] backfillEmployees() {
        int handled = 0;
        int skipped = 0;
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
            }
        }
        return new int[]{handled, skipped};
    }
}
