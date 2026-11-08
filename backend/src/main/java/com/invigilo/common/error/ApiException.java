package com.invigilo.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base for expected, client-facing failures. {@code code} is a stable machine-readable
 * identifier (e.g. {@code FACULTY_NOT_FOUND}, {@code ASSIGNMENT_CONFLICT}) that the frontend
 * can switch on; {@code message} is for humans.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 404. Also used for resources that exist in <em>another</em> college, so tenants cannot probe ids. */
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    /** 401. Bad credentials or an unusable refresh token. */
    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    /** 403. Authenticated, but not allowed (wrong role, disabled account, no membership). */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    /** 409. State conflicts such as duplicate codes or publishing a chart with blocking errors. */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422. Request is well-formed but violates a business rule. */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
