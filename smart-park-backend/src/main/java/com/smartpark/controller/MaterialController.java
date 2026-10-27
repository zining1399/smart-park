package com.smartpark.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartpark.common.PageResult;
import com.smartpark.common.PageSupport;
import com.smartpark.common.Result;
import com.smartpark.dto.MaterialCreateRequest;
import com.smartpark.dto.MaterialUpdateRequest;
import com.smartpark.entity.Material;
import com.smartpark.service.MaterialService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public Result<PageResult<Material>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String code,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String status) {
        Page<Material> page = materialService.pageQuery(
                PageSupport.clampPageNum(pageNum), PageSupport.clampPageSize(pageSize), name, code, category, status);
        return Result.ok(PageResult.of(page));
    }

    @GetMapping("/{id}")
    public Result<Material> detail(@PathVariable Long id) {
        return Result.ok(materialService.getById(id));
    }

    @PostMapping
    public Result<Material> create(@Valid @RequestBody MaterialCreateRequest request) {
        Material material = new Material();
        material.setCode(request.getCode());
        material.setName(request.getName());
        material.setCategory(request.getCategory());
        material.setSpec(request.getSpec());
        material.setDept(request.getDept());
        material.setLocation(request.getLocation());
        material.setValuable(request.getValuable());
        return Result.ok(materialService.create(material));
    }

    /** 物资编码与状态均不可由外部修改：编码被流水引用，状态由出入库流程维护 */
    @PutMapping("/{id}")
    public Result<Material> update(@PathVariable Long id, @Valid @RequestBody MaterialUpdateRequest request) {
        Material material = new Material();
        material.setId(id);
        material.setName(request.getName());
        material.setCategory(request.getCategory());
        material.setSpec(request.getSpec());
        material.setDept(request.getDept());
        material.setLocation(request.getLocation());
        material.setValuable(request.getValuable());
        return Result.ok(materialService.update(material));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        materialService.delete(id);
        return Result.ok();
    }
}
