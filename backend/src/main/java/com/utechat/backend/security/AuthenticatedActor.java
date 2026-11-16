package com.utechat.backend.security;

import java.util.UUID;

/** Danh tính lấy từ JWT (ADR-018): app không bao giờ gửi userId/deviceId để tự khai. */
public record AuthenticatedActor(UUID userId, String deviceIdHash) { }
