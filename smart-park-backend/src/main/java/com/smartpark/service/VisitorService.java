package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.entity.Visitor;

public interface VisitorService extends IService<Visitor> {

    /** 分页查询（返回前身份证已脱敏），按 id 倒序 */
    Page<Visitor> pageQuery(int pageNum, int pageSize, String visitorName, String hostName, String status);

    /** 详情（身份证脱敏） */
    Visitor getMasked(Long id);

    Visitor create(Visitor visitor);

    Visitor update(Visitor visitor);

    void delete(Long id);

    /** 签到：仅「已预约」→ 在场 */
    Visitor checkIn(Long id);

    /** 签离：「在场」/「滞留预警」→ 已签销 */
    Visitor checkOut(Long id);
}
