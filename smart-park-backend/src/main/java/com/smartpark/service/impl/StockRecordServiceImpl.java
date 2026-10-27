package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.DateTimes;
import com.smartpark.common.PageSupport;
import com.smartpark.dto.StockInRequest;
import com.smartpark.dto.StockOutRequest;
import com.smartpark.entity.Material;
import com.smartpark.entity.StockRecord;
import com.smartpark.mapper.MaterialMapper;
import com.smartpark.mapper.StockRecordMapper;
import com.smartpark.service.StockRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出入库：所有写 material 状态与写 stock_record 状态的动作均为「条件 UPDATE + 影响行数校验」（裁决 1），
 * 不再使用 read-check-write，杜绝并发双出库 / 双审批 / 重复归还。
 */
@Slf4j
@Service
public class StockRecordServiceImpl extends ServiceImpl<StockRecordMapper, StockRecord>
        implements StockRecordService {

    private final MaterialMapper materialMapper;
    private final AuditLogger auditLogger;

    public StockRecordServiceImpl(MaterialMapper materialMapper, AuditLogger auditLogger) {
        this.materialMapper = materialMapper;
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<StockRecord> pageQuery(int pageNum, int pageSize,
                                       String materialName, String type, String approveStatus, String borrower) {
        LambdaQueryWrapper<StockRecord> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(materialName), StockRecord::getMaterialName, materialName)
                .eq(StringUtils.hasText(type), StockRecord::getType, type)
                .eq(StringUtils.hasText(approveStatus), StockRecord::getApproveStatus, approveStatus)
                .like(StringUtils.hasText(borrower), StockRecord::getBorrower, borrower)
                .orderByDesc(StockRecord::getId);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockRecord stockIn(StockInRequest request) {
        Material material = requireMaterial(request.getMaterialCode());
        // S19：物资在外（在借/逾期）时不允许重复入库，避免 OUT 单未闭环却把物资置「在库」
        if ("在借".equals(material.getStatus()) || "逾期".equals(material.getStatus())) {
            throw BizException.conflict("物资当前状态为「" + material.getStatus() + "」，请先办理归还再入库");
        }

        StockRecord record = new StockRecord();
        record.setMaterialCode(material.getCode());
        record.setMaterialName(material.getName());
        record.setType("IN");
        record.setApproveStatus("免审");
        record.setCreateTime(DateTimes.now());
        record.setRemark(request.getRemark());
        save(record);

        int rows = materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                .eq(Material::getId, material.getId())
                .in(Material::getStatus, "维修中", "在库")
                .set(Material::getStatus, "在库"));
        if (rows != 1) {
            throw BizException.conflict("物资状态已变更，请刷新后重试");
        }
        log.info("入库成功 materialCode={} recordId={}", material.getCode(), record.getId());
        auditLogger.audit("STOCK_IN", material.getCode(), "SUCCESS", "recordId=" + record.getId());
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockRecord stockOut(StockOutRequest request) {
        Material material = requireMaterial(request.getMaterialCode());
        if (!"在库".equals(material.getStatus())) {
            throw BizException.conflict("物资当前状态为「" + material.getStatus() + "」，不可出库");
        }
        // 领用人在该物资类别上有逾期未还记录 → 拒绝
        checkOverdueSameCategory(request.getBorrower(), material.getCategory());

        StockRecord record = new StockRecord();
        record.setMaterialCode(material.getCode());
        record.setMaterialName(material.getName());
        record.setType("OUT");
        record.setBorrower(request.getBorrower());
        record.setDept(request.getDept());
        record.setExpectReturnTime(request.getExpectReturnTime());
        record.setCreateTime(DateTimes.now());
        record.setRemark(request.getRemark());

        if (Boolean.TRUE.equals(material.getValuable())) {
            // 裁决 12：贵重物资入口校验——同一物资存在未完成「待审批」单则拒绝，避免产生无法闭环的死单
            Long pending = count(new LambdaQueryWrapper<StockRecord>()
                    .eq(StockRecord::getMaterialCode, material.getCode())
                    .eq(StockRecord::getType, "OUT")
                    .eq(StockRecord::getApproveStatus, "待审批"));
            if (pending != null && pending > 0) {
                throw BizException.conflict("该物资已有待审批的申领单，请先完成审批");
            }
            // 贵重物资 → 待审批，物资状态不变（并发由 approve() 的条件更新兜住）
            record.setApproveStatus("待审批");
            save(record);
        } else {
            // 普通物资 → 免审直接出库：条件更新 在库→在借，失败说明已被并发领用
            record.setApproveStatus("免审");
            record.setOutTime(DateTimes.now());
            save(record);
            int rows = materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                    .eq(Material::getId, material.getId())
                    .eq(Material::getStatus, "在库")
                    .set(Material::getStatus, "在借"));
            if (rows != 1) {
                throw BizException.conflict("物资状态已变更（可能已被领用），请刷新后重试");
            }
        }
        log.info("出库申请成功 materialCode={} recordId={} approveStatus={}",
                material.getCode(), record.getId(), record.getApproveStatus());
        auditLogger.audit("STOCK_OUT", material.getCode(),
                "待审批".equals(record.getApproveStatus()) ? "PENDING_APPROVE" : "SUCCESS",
                "recordId=" + record.getId() + " borrower=" + record.getBorrower()
                        + " valuable=" + material.getValuable());
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockRecord approve(Long id, boolean approved) {
        StockRecord record = requireRecord(id);
        if (!"待审批".equals(record.getApproveStatus())) {
            throw BizException.conflict("该记录审批状态为「" + record.getApproveStatus() + "」，不可审批");
        }
        if (!approved) {
            int rows = baseMapper.update(null, new LambdaUpdateWrapper<StockRecord>()
                    .eq(StockRecord::getId, id)
                    .eq(StockRecord::getApproveStatus, "待审批")
                    .set(StockRecord::getApproveStatus, "已驳回"));
            if (rows != 1) {
                throw BizException.conflict("审批状态已变更，请刷新后重试");
            }
            record.setApproveStatus("已驳回");
            auditLogger.audit("APPROVE", String.valueOf(id), "REJECT",
                    "materialCode=" + record.getMaterialCode() + " borrower=" + record.getBorrower());
            return record;
        }

        Material material = requireMaterial(record.getMaterialCode());
        if (!"在库".equals(material.getStatus())) {
            throw BizException.conflict("物资当前状态为「" + material.getStatus() + "」，无法执行出库");
        }
        String now = DateTimes.now();
        // 条件更新记录：待审批 → 已批准（防双击重复审批）
        int rows = baseMapper.update(null, new LambdaUpdateWrapper<StockRecord>()
                .eq(StockRecord::getId, id)
                .eq(StockRecord::getApproveStatus, "待审批")
                .set(StockRecord::getApproveStatus, "已批准")
                .set(StockRecord::getOutTime, now));
        if (rows != 1) {
            throw BizException.conflict("审批状态已变更（可能已被其他审批人处理），请刷新后重试");
        }
        // 条件更新物资：在库 → 在借（防并发出库/审批叠加；与 stockOut 免审分支共用同一把"条件锁"）
        int mRows = materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                .eq(Material::getId, material.getId())
                .eq(Material::getStatus, "在库")
                .set(Material::getStatus, "在借"));
        if (mRows != 1) {
            throw BizException.conflict("物资状态已变更，请刷新后重试");
        }
        record.setApproveStatus("已批准");
        record.setOutTime(now);
        log.info("出库审批通过 recordId={} materialCode={}", id, material.getCode());
        auditLogger.audit("APPROVE", String.valueOf(id), "APPROVED",
                "materialCode=" + material.getCode() + " borrower=" + record.getBorrower()
                        + " valuable=" + material.getValuable());
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockRecord returnBack(Long id) {
        StockRecord record = requireRecord(id);
        if (!"OUT".equals(record.getType())) {
            throw BizException.conflict("仅出库记录可执行归还");
        }
        if (record.getReturnTime() != null) {
            throw BizException.conflict("该记录已归还，请勿重复操作");
        }
        if (!"免审".equals(record.getApproveStatus()) && !"已批准".equals(record.getApproveStatus())) {
            throw BizException.conflict("该出库单未通过审批，未实际出库，不可归还");
        }
        String now = DateTimes.now();
        // 条件更新记录：仅当 return_time 仍为空（防重复归还）
        int rows = baseMapper.update(null, new LambdaUpdateWrapper<StockRecord>()
                .eq(StockRecord::getId, id)
                .isNull(StockRecord::getReturnTime)
                .in(StockRecord::getApproveStatus, "免审", "已批准")
                .set(StockRecord::getReturnTime, now));
        if (rows != 1) {
            throw BizException.conflict("归还状态已变更（可能已归还），请刷新后重试");
        }
        // 条件更新物资：在借/逾期 → 在库
        Material material = requireMaterial(record.getMaterialCode());
        int mRows = materialMapper.update(null, new LambdaUpdateWrapper<Material>()
                .eq(Material::getId, material.getId())
                .in(Material::getStatus, "在借", "逾期")
                .set(Material::getStatus, "在库"));
        if (mRows != 1) {
            throw BizException.conflict("物资状态已变更，请刷新后重试");
        }
        record.setReturnTime(now);
        log.info("归还成功 recordId={} materialCode={}", id, material.getCode());
        auditLogger.audit("STOCK_RETURN", String.valueOf(id), "SUCCESS",
                "materialCode=" + material.getCode() + " borrower=" + record.getBorrower());
        return record;
    }

    private Material requireMaterial(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("物资编码不能为空");
        }
        // S12：业务唯一键统一大写归一化
        String normalized = code.trim().toUpperCase();
        Material material = materialMapper.selectOne(
                new LambdaQueryWrapper<Material>().eq(Material::getCode, normalized));
        if (material == null) {
            throw BizException.notFound("物资不存在：" + normalized);
        }
        return material;
    }

    private StockRecord requireRecord(Long id) {
        StockRecord record = getById(id);
        if (record == null) {
            throw BizException.notFound("出入库记录不存在");
        }
        return record;
    }

    /**
     * 领用人在同 category 下有逾期未还记录 → 拒绝（批量查询 material，避免 N+1）。
     */
    private void checkOverdueSameCategory(String borrower, String category) {
        if (!StringUtils.hasText(category)) {
            return;
        }
        List<StockRecord> overdueList = list(new LambdaQueryWrapper<StockRecord>()
                .eq(StockRecord::getBorrower, borrower)
                .eq(StockRecord::getType, "OUT")
                .in(StockRecord::getApproveStatus, "免审", "已批准")
                .isNull(StockRecord::getReturnTime)
                .lt(StockRecord::getExpectReturnTime, DateTimes.now()));
        if (overdueList.isEmpty()) {
            return;
        }
        List<String> codes = overdueList.stream()
                .map(StockRecord::getMaterialCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, Material> materials = materialMapper.selectList(
                        new LambdaQueryWrapper<Material>().in(Material::getCode, codes))
                .stream()
                .collect(Collectors.toMap(Material::getCode, Function.identity(), (a, b) -> a));
        for (StockRecord r : overdueList) {
            Material m = materials.get(r.getMaterialCode());
            if (m != null && category.equals(m.getCategory())) {
                throw BizException.conflict("领用人「" + borrower + "」在「" + category
                        + "」类别下有逾期未还记录（" + r.getMaterialName() + "），禁止再次领用");
            }
        }
    }
}
