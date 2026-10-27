package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.Material;

public interface MaterialService extends IService<Material> {

    Page<Material> pageQuery(int pageNum, int pageSize, String name, String code, String category, String status);

    Material create(Material material);

    Material update(Material material);

    void delete(Long id);
}
