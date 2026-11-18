package com.utechat.data.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import okhttp3.ResponseBody;
import retrofit2.Response;

/** Đọc lỗi REST danh bạ; không thay đổi phiên, cache hoặc tự thử lại thao tác ghi. */
public final class ContactErrorMapper {
    private ContactErrorMapper() { }

    /** Chỉ nhận HTTP lỗi; tiêu thụ và đóng errorBody nên caller không đọc lại thân lỗi. */
    public static <T> ContactsResult<T> fromHttp(Response<?> response) {
        if (response.isSuccessful()) {
            throw new IllegalArgumentException("Chỉ ánh xạ phản hồi HTTP lỗi");
        }
        // Luôn có mã dự phòng: proxy trả HTML hoặc JSON thiếu code vẫn là thất bại.
        ContactsResult<T> result = ContactsResult.error("HTTP_" + response.code());
        try (ResponseBody body = response.errorBody()) {
            if (body == null) return result;
            JsonObject root = object(JsonParser.parseString(body.string()));
            JsonObject error = root == null ? null : object(root.get("error"));
            if (error == null) return result;
            String code = text(error.get("code"));
            if (code == null) return result;
            result.errorCode = code;

            // Đọc từng metadata độc lập; một trường sai không làm mất code hoặc trường hợp lệ khác.
            JsonObject details = object(error.get("details"));
            if (details != null) {
                result.retryAfterSeconds = nonNegativeInt(details.get("retryAfterSeconds"));
                result.existingRequestId = text(details.get("existingRequestId"));
                result.direction = text(details.get("direction"));
            }
        } catch (IOException | JsonParseException ignored) {
            // Đã nhận HTTP lỗi: lỗi đọc/parse body không biến thành thành công hoặc lỗi NETWORK.
        }
        return result;
    }

    /** Chỉ nhận object JSON thật, bỏ qua null hoặc cấu trúc khác kiểu. */
    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** Không để Gson ép số/boolean thành chuỗi code hoặc metadata. */
    private static String text(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString();
        return text.trim().isEmpty() ? null : text;
    }

    /** Thời gian chờ phải là số nguyên không âm trong miền int; không cắt thập phân hoặc tràn số. */
    private static int nonNegativeInt(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        try {
            int seconds = value.getAsBigDecimal().intValueExact();
            return Math.max(0, seconds);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return 0;
        }
    }
}
