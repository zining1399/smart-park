package com.smartpark.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1062 唯一键冲突映射与错误码→HTTP 状态码映射的回归测试。
 * 价值：将来新增/改名唯一约束，或有人把 409 写成 500，这里会立刻炸。
 */
class GlobalExceptionHandlerTest {

    private static final String RAW = "Duplicate entry 'X' for key 'smart_park.%s'";

    @Test
    void duplicateMessage_mapsEveryUniqueConstraint() {
        assertEquals("工号已存在，请勿重复提交",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_employee_emp_no")));
        assertEquals("车牌号已存在，请勿重复提交",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_vehicle_plate_no")));
        assertEquals("物资编码已存在，请勿重复提交",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_material_code")));
        assertEquals("登录名已存在（不区分大小写），请更换",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_sys_user_username")));
        assertEquals("该告警已存在（去重键冲突），无需重复上报",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_alarm_dedup_key")));
    }

    @Test
    void duplicateMessage_fallsBackWhenConstraintUnknown() {
        assertEquals("数据已存在，请勿重复提交",
                GlobalExceptionHandler.duplicateMessage(String.format(RAW, "uk_something_new")));
        assertEquals("数据已存在，请勿重复提交", GlobalExceptionHandler.duplicateMessage(null));
        assertEquals("数据已存在，请勿重复提交", GlobalExceptionHandler.duplicateMessage("no constraint here"));
    }

    @Test
    void httpStatusOf_coversFrozenCodes() {
        assertEquals(HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.httpStatusOf(ErrorCodes.UNAUTHORIZED));
        assertEquals(HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.httpStatusOf(ErrorCodes.LOGIN_FAILED));
        assertEquals(HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.httpStatusOf(ErrorCodes.ACCOUNT_DISABLED));
        assertEquals(HttpStatus.FORBIDDEN, GlobalExceptionHandler.httpStatusOf(ErrorCodes.FORBIDDEN));
        assertEquals(HttpStatus.NOT_FOUND, GlobalExceptionHandler.httpStatusOf(ErrorCodes.NOT_FOUND));
        assertEquals(HttpStatus.CONFLICT, GlobalExceptionHandler.httpStatusOf(ErrorCodes.CONFLICT));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                GlobalExceptionHandler.httpStatusOf(ErrorCodes.SYSTEM_ERROR));
        assertEquals(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.httpStatusOf(ErrorCodes.PARAM_ERROR));
    }
}
