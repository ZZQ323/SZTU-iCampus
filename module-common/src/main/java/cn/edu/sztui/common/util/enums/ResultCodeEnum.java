package cn.edu.sztui.common.util.enums;

public enum ResultCodeEnum {
    SUCCESS(200, 200, "SUCCESS"),
    CREATED(201, 201, "SUCCESS"),
    NO_CONTENT(204, 204, "SUCCESS"),
    BAD_REQUEST(400, 400, "BAD_REQUEST"),
    UNAUTHORIZED(401, 401, "UNAUTHORIZED"),
    FORBIDDEN(403, 403, "FORBIDDEN"),
    NOFOUND(404, 404, "NOT_FOUND"),
    GATEWAY_TIMEOUT(405, 405, "TIMEOUT"),
    CONFLICT(409, 409, "CONFLICT"),
    INTERNAL_SERVER_ERROR(500, 500, "INTERNAL_SERVER_ERROR");

    public int code;
    public int httpStatus;
    public String errorMsg;

    private ResultCodeEnum(int code, int httpStatus, String errorMsg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.errorMsg = errorMsg;
    }

    public int getCode() {
        return this.code;
    }

    public int getHttpStatus() {
        return this.httpStatus;
    }

    public String getErrorMsg() {
        return this.errorMsg;
    }
}
