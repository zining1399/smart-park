package com.smartpark.common;

/**
 * 全局错误码（数值冻结，前端 hard-code 分支，联调期间不得变更）。
 * 每个错误码绑定唯一 HTTP 状态码，由 GlobalExceptionHandler 统一输出。
 */
public final class ErrorCodes {

    /** 成功 */
    public static final int OK = 0;

    /** 参数校验失败 / 请求不合法 → HTTP 400 */
    public static final int PARAM_ERROR = 40001;

    /** 未登录：token 缺失/过期/无效 → HTTP 401 */
    public static final int UNAUTHORIZED = 40101;

    /** 登录失败：用户名或密码错误（仅 /auth/login 出现）→ HTTP 401 */
    public static final int LOGIN_FAILED = 40102;

    /** 登录失败：账号被禁用（仅 /auth/login 出现）→ HTTP 401 */
    public static final int ACCOUNT_DISABLED = 40103;

    /** 已登录但角色无权限 → HTTP 403 */
    public static final int FORBIDDEN = 40301;

    /** 资源不存在 → HTTP 404 */
    public static final int NOT_FOUND = 40401;

    /** 状态冲突 / 重复操作（唯一键冲突、并发状态变更失败）→ HTTP 409 */
    public static final int CONFLICT = 40901;

    /** 系统异常 → HTTP 500（对外仅通用文案，详情只进日志） */
    public static final int SYSTEM_ERROR = 50001;

    private ErrorCodes() {
    }
}
