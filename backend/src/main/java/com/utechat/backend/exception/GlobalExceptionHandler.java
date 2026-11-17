package com.utechat.backend.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Gom mọi lỗi về một dạng JSON: { "error": { "code", "message", "details" } } (API_CONTRACT mục 1). */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), e.getDetails());
    }

    /** JSON sai cú pháp hoặc sai kiểu: coi là dữ liệu không hợp lệ. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", "Invalid request", Map.of());
    }

    /** Thiếu query bắt buộc là lỗi nhập liệu, không để rơi vào handler 500 chung. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException e) {
        String code = "phone".equals(e.getParameterName()) ? "PHONE_INVALID" : "VALIDATION";
        return body(HttpStatus.BAD_REQUEST, code, "Missing required parameter: " + e.getParameterName(), Map.of());
    }

    /** Lỗi không lường trước: ghi log nội bộ, trả thông điệp chung để không lộ SQL hay stacktrace. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Lỗi không xử lý được", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Internal error", Map.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, Map<String, Object> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message); // Chỉ để gỡ lỗi; app dịch theo code.
        if (!details.isEmpty()) error.put("details", details);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
