package com.utechat.backend.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Lỗi nghiệp vụ có mã HTTP và mã lỗi cố định (API_CONTRACT mục 1 và 5); app tự dịch `code` thành chữ. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details; // Ví dụ retryAfterSeconds; có thể rỗng.

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
