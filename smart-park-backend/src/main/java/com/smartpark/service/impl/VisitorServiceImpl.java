package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.DateTimes;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.Visitor;
import com.smartpark.mapper.VisitorMapper;
import com.smartpark.security.crypto.SensitiveCrypto;
import com.smartpark.service.VisitorService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VisitorServiceImpl extends ServiceImpl<VisitorMapper, Visitor> implements VisitorService {

    private final SensitiveCrypto crypto;
    private final AuditLogger auditLogger;

    public VisitorServiceImpl(SensitiveCrypto crypto, AuditLogger auditLogger) {
        this.crypto = crypto;
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<Visitor> pageQuery(int pageNum, int pageSize, String visitorName, String hostName, String status) {
        LambdaQueryWrapper<Visitor> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(visitorName), Visitor::getVisitorName, visitorName)
                .like(StringUtils.hasText(hostName), Visitor::getHostName, hostName)
                .eq(StringUtils.hasText(status), Visitor::getStatus, status)
                .orderByDesc(Visitor::getId);
        Page<Visitor> page = page(new Page<>(PageSupport.clampPageNum(pageNum),
                PageSupport.clampPageSize(pageSize)), qw);
        page.getRecords().forEach(this::toMaskedView);
        return page;
    }

    @Override
    public Visitor getMasked(Long id) {
        Visitor visitor = getById(id);
        if (visitor == null) {
            throw BizException.notFound("访客记录不存在");
        }
        return toMaskedView(visitor);
    }

    @Override
    public Visitor create(Visitor visitor) {
        if (!StringUtils.hasText(visitor.getVisitorName()) || !StringUtils.hasText(visitor.getPhone())) {
            throw new BizException("访客姓名和手机号不能为空");
        }
        visitor.setId(null);
        visitor.setStatus("已预约");
        visitor.setCheckInTime(null);
        visitor.setCheckOutTime(null);
        // 明文入库前加密：cipher + hash（等值检索）+ mask（展示）
        applySensitive(visitor, visitor.getIdCard(), visitor.getPhone());
        save(visitor);
        return toMaskedView(visitor);
    }

    @Override
    public Visitor update(Visitor visitor) {
        Visitor exist = getById(visitor.getId());
        if (exist == null) {
            throw BizException.notFound("访客记录不存在");
        }
        // 状态流转只能走 check-in / check-out / 定时任务，普通修改不允许直接改状态与进出时间
        visitor.setStatus(exist.getStatus());
        visitor.setCheckInTime(exist.getCheckInTime());
        visitor.setCheckOutTime(exist.getCheckOutTime());
        // B05 三层防护后端侧：身份证/手机号若是掩码形态（含 *），说明前端回传的是展示值，不更新该字段
        String newIdCard = SensitiveCrypto.looksMasked(visitor.getIdCard()) ? null : visitor.getIdCard();
        String newPhone = SensitiveCrypto.looksMasked(visitor.getPhone()) ? null : visitor.getPhone();
        applySensitive(visitor, newIdCard, newPhone);
        updateById(visitor);
        return getMasked(visitor.getId());
    }

    @Override
    public void delete(Long id) {
        Visitor exist = getById(id);
        if (exist == null) {
            throw BizException.notFound("访客记录不存在");
        }
        removeById(id);
        // 只记掩码，审计日志不落明文身份证/手机号
        auditLogger.audit("DELETE_VISITOR", String.valueOf(id), "SUCCESS",
                "name=" + exist.getVisitorName() + " phoneMask=" + exist.getPhoneMask());
    }

    @Override
    public Visitor checkIn(Long id) {
        Visitor visitor = getById(id);
        if (visitor == null) {
            throw BizException.notFound("访客记录不存在");
        }
        if (!"已预约".equals(visitor.getStatus())) {
            throw BizException.conflict("当前状态为「" + visitor.getStatus() + "」，仅「已预约」可签到进场");
        }
        visitor.setStatus("在场");
        visitor.setCheckInTime(DateTimes.now());
        updateById(visitor);
        auditLogger.audit("VISITOR_CHECK_IN", String.valueOf(id), "SUCCESS",
                "name=" + visitor.getVisitorName() + " host=" + visitor.getHostName());
        return getMasked(id);
    }

    @Override
    public Visitor checkOut(Long id) {
        Visitor visitor = getById(id);
        if (visitor == null) {
            throw BizException.notFound("访客记录不存在");
        }
        if (!"在场".equals(visitor.getStatus()) && !"滞留预警".equals(visitor.getStatus())) {
            throw BizException.conflict("当前状态为「" + visitor.getStatus() + "」，仅「在场」或「滞留预警」可签离");
        }
        visitor.setStatus("已签销");
        visitor.setCheckOutTime(DateTimes.now());
        updateById(visitor);
        auditLogger.audit("VISITOR_CHECK_OUT", String.valueOf(id), "SUCCESS",
                "name=" + visitor.getVisitorName() + " host=" + visitor.getHostName());
        return getMasked(id);
    }

    /** 写入密文/哈希/掩码三列；plain 为 null 表示不更新该敏感字段 */
    private void applySensitive(Visitor visitor, String plainIdCard, String plainPhone) {
        if (plainIdCard == null) {
            visitor.setIdCard(null);
            visitor.setIdCardHash(null);
            visitor.setIdCardMask(null);
        } else {
            visitor.setIdCard(crypto.encrypt(plainIdCard));
            visitor.setIdCardHash(crypto.hash(plainIdCard));
            visitor.setIdCardMask(crypto.maskIdCard(plainIdCard));
        }
        if (plainPhone == null) {
            visitor.setPhone(null);
            visitor.setPhoneHash(null);
            visitor.setPhoneMask(null);
        } else {
            visitor.setPhone(crypto.encrypt(plainPhone));
            visitor.setPhoneHash(crypto.hash(plainPhone));
            visitor.setPhoneMask(crypto.maskPhone(plainPhone));
        }
    }

    /** 出参视图：敏感字段一律替换为掩码，永不返回密文或明文 */
    private Visitor toMaskedView(Visitor v) {
        v.setIdCard(v.getIdCardMask());
        v.setPhone(v.getPhoneMask());
        v.setIdCardHash(null);
        v.setPhoneHash(null);
        return v;
    }
}
