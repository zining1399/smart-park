package com.smartpark.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartpark.dto.StockInRequest;
import com.smartpark.dto.StockOutRequest;
import com.smartpark.entity.StockRecord;

public interface StockRecordService extends IService<StockRecord> {

    Page<StockRecord> pageQuery(int pageNum, int pageSize,
                                String materialName, String type, String approveStatus, String borrower);

    /** 入库：物资状态置「在库」 */
    StockRecord stockIn(StockInRequest request);

    /** 申领出库：贵重物资待审批，普通物资免审直接出库 */
    StockRecord stockOut(StockOutRequest request);

    /** 审批：批准→执行出库；驳回→置已驳回 */
    StockRecord approve(Long id, boolean approved);

    /** 归还：写 returnTime，物资回切「在库」 */
    StockRecord returnBack(Long id);
}
