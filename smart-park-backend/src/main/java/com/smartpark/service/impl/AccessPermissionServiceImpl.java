package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.DateTimes;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.AccessPermission;
import com.smartpark.mapper.AccessPermissionMapper;
import com.smartpark.service.AccessPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccessPermissionServiceImpl extends ServiceImpl<AccessPermissionMapper, AccessPermission>
        implements AccessPermissionService {

    private final AuditLogger auditLogger;

    public AccessPermissionServiceImpl(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<AccessPermission> pageQuery(int pageNum, int pageSize,
                                            String targetType, String targetNo, String area, String status) {
        LambdaQueryWrapper<AccessPermission> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(targetType), AccessPermission::getTargetType, targetType)
                .like(StringUtils.hasText(targetNo), AccessPermission::getTargetNo, targetNo)
                .eq(StringUtils.hasText(area), AccessPermission::getArea, area)
                .eq(StringUtils.hasText(status), AccessPermission::getStatus, status)
                .orderByDesc(AccessPermission::getId);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    public AccessPermission create(AccessPermission permission) {
        validate(permission);
        // S12：targetNo 引用工号/车牌，统一大写归一化，避免大小写不一致导致权限匹配失败
        permission.setTargetNo(permission.getTargetNo().trim().toUpperCase());
        permission.setStatus(calcStatus(permission.getEndTime()));
        save(permission);
        auditLogger.audit("PERMISSION_GRANT", permission.getTargetType() + ":" + permission.getTargetNo(),
                "SUCCESS", "area=" + permission.getArea() + " status=" + permission.getStatus()
                        + " endTime=" + permission.getEndTime());
        return permission;
    }

    @Override
    public AccessPermission update(AccessPermission permission) {
        if (getById(permission.getId()) == null) {
            throw new BizException("权限记录不存在");
        }
        validate(permission);
        permission.setTargetNo(permission.getTargetNo().trim().toUpperCase());
        permission.setStatus(calcStatus(permission.getEndTime()));
        updateById(permission);
        return getById(permission.getId());
    }

    @Override
    public void delete(Long id) {
        AccessPermission exist = getById(id);
        if (exist == null) {
            throw BizException.notFound("权限记录不存在");
        }
        removeById(id);
        // 权限回收属高敏操作，必须留痕
        auditLogger.audit("PERMISSION_REVOKE", exist.getTargetType() + ":" + exist.getTargetNo(),
                "SUCCESS", "id=" + id + " area=" + exist.getArea());
    }

    @Override
    public String calcStatus(String endTime) {
        String now = DateTimes.now();
        if (endTime.compareTo(now) < 0) {
            return "失效";
        }
        // 7 天内到期 → 临期
        if (endTime.compareTo(DateTimes.plusDays(7)) <= 0) {
            return "临期";
        }
        return "生效";
    }

    private void validate(AccessPermission p) {
        if (!StringUtils.hasText(p.getTargetType()) || !StringUtils.hasText(p.getTargetNo())
                || !StringUtils.hasText(p.getArea()) || !StringUtils.hasText(p.getStartTime())
                || !StringUtils.hasText(p.getEndTime())) {
            throw new BizException("targetType、targetNo、area、startTime、endTime 均不能为空");
        }
        if (!"PERSON".equals(p.getTargetType()) && !"VEHICLE".equals(p.getTargetType())) {
            throw new BizException("targetType 仅支持 PERSON / VEHICLE");
        }
        // area 取值校验：MySQL 5.7 下 CHECK 会静默失效，应用层这道必须与 DTO @Pattern 双重存在
        if (!"办公区".equals(p.getArea()) && !"生产区".equals(p.getArea())
                && !"仓储区".equals(p.getArea()) && !"生活区".equals(p.getArea())) {
            throw new BizException("area 仅支持 办公区 / 生产区 / 仓储区 / 生活区");
        }
        if (p.getEndTime().compareTo(p.getStartTime()) < 0) {
            throw new BizException("失效时间不能早于生效时间");
        }
    }
}
