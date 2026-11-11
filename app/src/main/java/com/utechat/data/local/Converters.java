package com.utechat.data.local;

import androidx.room.TypeConverter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** SQLite lưu kiểu nguyên thủy; converter giữ được null và dữ liệu có dấu. */
public class Converters {
    private static final Gson GSON = new Gson(); // Dùng lại Gson cho JSON danh sách.

    @TypeConverter public static Long dateToLong(Date value) { return value == null ? null : value.getTime(); }
    @TypeConverter public static Date longToDate(Long value) { return value == null ? null : new Date(value); }

    // UUID lưu dạng chuỗi chuẩn; chuỗi sai bị từ chối để không tạo định danh giả.
    @TypeConverter public static String uuidToString(UUID value) { return value == null ? null : value.toString(); }
    @TypeConverter public static UUID stringToUuid(String value) { return value == null ? null : UUID.fromString(value); }

    // JSON giữ nguyên dấu tiếng Việt, dấu phẩy và dấu ngoặc trong từng phần tử.
    @TypeConverter public static String listToString(List<String> value) { return value == null ? null : GSON.toJson(value); }
    @TypeConverter public static List<String> stringToList(String value) {
        return value == null ? null : GSON.fromJson(value, new TypeToken<List<String>>() { }.getType());
    }
}
