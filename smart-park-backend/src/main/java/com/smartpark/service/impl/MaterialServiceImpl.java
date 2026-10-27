package com.smartpark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartpark.common.AuditLogger;
import com.smartpark.common.BizException;
import com.smartpark.common.PageSupport;
import com.smartpark.entity.Material;
import com.smartpark.mapper.MaterialMapper;
import com.smartpark.service.MaterialService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MaterialServiceImpl extends ServiceImpl<MaterialMapper, Material> implements MaterialService {

    private final AuditLogger auditLogger;

    public MaterialServiceImpl(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public Page<Material> pageQuery(int pageNum, int pageSize, String name, String code, String category, String status) {
        LambdaQueryWrapper<Material> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(name), Material::getName, name)
                .like(StringUtils.hasText(code), Material::getCode, code)
                .eq(StringUtils.hasText(category), Material::getCategory, category)
                .eq(StringUtils.hasText(status), Material::getStatus, status)
                .orderByDesc(Material::getId);
        return page(new Page<>(PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize)), qw);
    }

    @Override
    public Material create(Material material) {
        if (!StringUtils.hasText(material.getCode()) || !StringUtils.hasText(material.getName())) {
            throw new BizException("物资编码和名称不能为空");
        }
        // S12：业务唯一键统一大写归一化
        material.setCode(material.getCode().trim().toUpperCase());
        if (material.getValuable() == null) {
            material.setValuable(false);
        }
        // 状态由服务端维护：新建物资固定「在库」，不信任前端传入值
        material.setStatus("在库");
        Long count = count(new LambdaQueryWrapper<Material>().eq(Material::getCode, material.getCode()));
        if (count > 0) {
            throw BizException.conflict("物资编码已存在：" + material.getCode());
        }
        save(material);
        return material;
    }

    @Override
    public Material update(Material material) {
        Material exist = getById(material.getId());
        if (exist == null) {
            throw BizException.notFound("物资不存在");
        }
        // S13：物资编码被出入库流水引用，禁止修改；状态由出入库流程维护，禁止外部直接改写
        material.setCode(exist.getCode());
        material.setStatus(exist.getStatus());
        updateById(material);
        return getById(material.getId());
    }

    @Override
    public void delete(Long id) {
        Material exist = getById(id);
        if (exist == null) {
            throw BizException.notFound("物资不存在");
        }
        if ("在借".equals(exist.getStatus()) || "逾期".equals(exist.getStatus())) {
            throw BizException.conflict("物资当前状态为「" + exist.getStatus() + "」，不可删除");
        }
        removeById(id);
        auditLogger.audit("DELETE_MATERIAL", exist.getCode(), "SUCCESS",
                "id=" + id + " name=" + exist.getName() + " valuable=" + exist.getValuable());
    }
}
