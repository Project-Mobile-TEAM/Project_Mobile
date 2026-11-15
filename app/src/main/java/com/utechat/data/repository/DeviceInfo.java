package com.utechat.data.repository;

/** Một thiết bị đang đăng nhập theo GET /account/devices (API_CONTRACT 2.2). */
public final class DeviceInfo {
    public final String id;
    public final String name; // Ví dụ "Pixel 7".
    public final String osVersion; // Ví dụ "Android 14".
    public final long lastSeenAtMillis; // Lần hoạt động gần nhất (epoch mili giây).
    public final boolean current; // Thiết bị đang dùng, không được tự thu hồi.

    public DeviceInfo(String id, String name, String osVersion, long lastSeenAtMillis, boolean current) {
        this.id = id;
        this.name = name;
        this.osVersion = osVersion;
        this.lastSeenAtMillis = lastSeenAtMillis;
        this.current = current;
    }
}
