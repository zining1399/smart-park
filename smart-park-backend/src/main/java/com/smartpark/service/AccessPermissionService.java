package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.AccessPermission;

public interface AccessPermissionService extends IService<AccessPermission> {

    Page<AccessPermission> pageQuery(int pageNum, int pageSize,
                                     String targetType, String targetNo, String area, String status);

    AccessPermission create(AccessPermission permission);

    AccessPermission update(AccessPermission permission);

    void delete(Long id);

    /**
     * 按有效期计算权限状态：生效 / 临期（7 天内到期）/ 失效。
     */
    String calcStatus(String endTime);
}
