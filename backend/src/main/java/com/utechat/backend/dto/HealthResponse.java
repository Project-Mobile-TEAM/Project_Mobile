package com.utechat.backend.dto;

/** DTO bất biến: Jackson chuyển tên các thành phần record thành trường JSON. */
public record HealthResponse(String status, String database, String timestamp) { }
