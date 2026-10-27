package com.smartpark.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.util.List;

/**
 * 分页响应：{ total, list }
 */
@Data
public class PageResult<T> {

    private Long total;
    private List<T> list;

    public static <T> PageResult<T> of(long total, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setList(list);
        return r;
    }

    public static <T> PageResult<T> of(Page<T> page) {
        return of(page.getTotal(), page.getRecords());
    }
}
