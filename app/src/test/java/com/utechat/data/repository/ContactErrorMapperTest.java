package com.utechat.data.repository;

import static org.junit.Assert.*;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.Test;
import retrofit2.Response;

/** Dùng Response Retrofit và JSON fixture thật; không phụ thuộc Room hoặc Android. */
public class ContactErrorMapperTest {
    private static final String REQUEST_ID = "33333333-3333-4333-8333-333333333333";

    /** Giữ mã giới hạn và số giây chờ do backend trả, không đánh dấu thành công. */
    @Test public void mapsRateLimitWithRetrySeconds() {
        ContactsResult<Boolean> result = map(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":12}}}");
        assertEquals("RATE_LIMITED", result.errorCode);
        assertEquals(12, result.retryAfterSeconds);
        assertFalse(result.isSuccess());
        assertNull(result.data);
        assertFalse(result.refreshRequired);
    }

    /** Lời mời nhận đến giữ ID và chiều từ details.direction. */
    @Test public void mapsIncomingPendingConflict() {
        ContactsResult<Boolean> result = pending("INCOMING");
        assertEquals("REQUEST_ALREADY_PENDING", result.errorCode);
        assertEquals(REQUEST_ID, result.existingRequestId);
        assertEquals("INCOMING", result.direction);
        assertFalse(result.isSuccess());
    }

    /** Chiều gửi đi không bị tự đổi sang nhận đến. */
    @Test public void mapsOutgoingPendingConflict() {
        assertEquals("OUTGOING", pending("OUTGOING").direction);
    }

    /** Chấp nhận mã lỗi mới; không đưa message gỡ lỗi hoặc metadata lạ vào kết quả UI. */
    @Test public void preservesUnknownCodeAndIgnoresExtraFields() {
        ContactsResult<Boolean> result = map(422,
                "{\"error\":{\"code\":\"NEW_POLICY\",\"message\":\"debug only\",\"extra\":true}}");
        assertEquals("NEW_POLICY", result.errorCode);
        assertFalse(result.isSuccess());
    }

    /** Thiếu code không được ghi null đè mã HTTP rồi biến thành thành công. */
    @Test public void missingCodeKeepsHttpFallback() {
        assertFallback(500, "{\"error\":{\"message\":\"Missing code\"}}");
    }

    /** Code null cũng phải giữ lỗi HTTP ban đầu. */
    @Test public void nullCodeKeepsHttpFallback() {
        assertFallback(400, "{\"error\":{\"code\":null}}");
    }

    /** Code rỗng hoặc toàn dấu cách không đủ để xác định mã nghiệp vụ. */
    @Test public void blankCodeKeepsHttpFallback() {
        for (String code : new String[]{"\"\"", "\"   \"", "\"\\t\\n\""}) {
            assertFallback(403, "{\"error\":{\"code\":" + code + "}}");
        }
    }

    /** Không ép các kiểu JSON khác thành chuỗi code. */
    @Test public void wrongCodeTypesKeepHttpFallback() {
        for (String code : new String[]{"123", "true", "{}", "[]"}) {
            assertFallback(500, "{\"error\":{\"code\":" + code + "}}");
        }
    }

    /** Thiếu hoặc sai cấu trúc envelope không được gây exception cho caller. */
    @Test public void missingOrWrongErrorEnvelopeKeepsHttpFallback() {
        for (String json : new String[]{"{}", "null", "[]", "true", "\"text\"",
                "{\"error\":null}", "{\"error\":[]}", "{\"error\":\"bad\"}"}) {
            assertFallback(502, json);
        }
    }

    /** Proxy có thể trả HTML, body rỗng hoặc JSON bị cắt; giữ mã HTTP thay vì crash. */
    @Test public void emptyHtmlAndBrokenJsonKeepHttpFallback() {
        for (String json : new String[]{"", " ", "<html>Bad gateway</html>", "{\"error\":"}) {
            assertFallback(503, json);
        }
    }

    /** Details vắng mặt, null hoặc sai kiểu không làm mất code hợp lệ. */
    @Test public void invalidOrAbsentDetailsKeepsValidCode() {
        for (String suffix : new String[]{"", ",\"details\":null", ",\"details\":[]",
                ",\"details\":false", ",\"details\":\"bad\""}) {
            ContactsResult<Boolean> result = map(429, "{\"error\":{\"code\":\"RATE_LIMITED\"" + suffix + "}}");
            assertEquals("RATE_LIMITED", result.errorCode);
            assertEquals(0, result.retryAfterSeconds);
            assertNull(result.existingRequestId);
            assertNull(result.direction);
        }
    }

    /** Chấp nhận cả hai biên của số nguyên không âm mà không overflow. */
    @Test public void retrySecondsAcceptsZeroAndMaxInt() {
        assertEquals(0, retry("0").retryAfterSeconds);
        assertEquals(Integer.MAX_VALUE, retry("2147483647").retryAfterSeconds);
    }

    /** Sai kiểu, số âm, thập phân hoặc vượt miền int được bỏ qua, vẫn giữ code. */
    @Test public void invalidRetrySecondsDoesNotWrapOrTruncate() {
        for (String seconds : new String[]{"null", "-1", "1.5", "2147483648", "1e100",
                "\"12\"", "true", "[]", "{}"}) {
            ContactsResult<Boolean> result = retry(seconds);
            assertEquals("RATE_LIMITED", result.errorCode);
            assertEquals(0, result.retryAfterSeconds);
        }
    }

    /** Một metadata sai không được làm mất ID/chiều còn hợp lệ. */
    @Test public void invalidRetryDoesNotDiscardPendingMetadata() {
        ContactsResult<Boolean> result = map(409,
                "{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{"
                        + "\"retryAfterSeconds\":{},\"existingRequestId\":\"" + REQUEST_ID
                        + "\",\"direction\":\"INCOMING\"}}}");
        assertEquals(REQUEST_ID, result.existingRequestId);
        assertEquals("INCOMING", result.direction);
        assertEquals(0, result.retryAfterSeconds);
    }

    /** Không ép số/boolean/object hoặc chuỗi trống thành ID/chiều lời mời. */
    @Test public void invalidPendingMetadataIsIgnoredIndependently() {
        for (String value : new String[]{"null", "123", "false", "{}", "[]", "\" \""}) {
            ContactsResult<Boolean> result = map(409,
                    "{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{"
                            + "\"existingRequestId\":" + value + ",\"direction\":\"OUTGOING\"}}}");
            assertNull(result.existingRequestId);
            assertEquals("OUTGOING", result.direction);
            result = map(409, "{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{"
                    + "\"existingRequestId\":\"" + REQUEST_ID + "\",\"direction\":" + value + "}}}");
            assertEquals(REQUEST_ID, result.existingRequestId);
            assertNull(result.direction);
        }
    }

    /** Không sử dụng metadata nếu envelope chưa có mã lỗi hợp lệ. */
    @Test public void metadataWithoutCodeDoesNotOverrideFallback() {
        ContactsResult<Boolean> result = map(429,
                "{\"error\":{\"details\":{\"retryAfterSeconds\":60,\"direction\":\"INCOMING\"}}}");
        assertEquals("HTTP_429", result.errorCode);
        assertEquals(0, result.retryAfterSeconds);
        assertNull(result.direction);
    }

    /** Đọc thành công phải đóng body sau khi lấy các trường cần thiết. */
    @Test public void closesBodyAfterValidJson() {
        TrackingBody body = new TrackingBody("{\"error\":{\"code\":\"BLOCKED\"}}", false);
        assertEquals("BLOCKED", ContactErrorMapper.fromHttp(Response.error(403, body)).errorCode);
        assertTrue(body.closed);
    }

    /** Nhánh JSON lỗi cũng phải đóng tài nguyên response. */
    @Test public void closesBodyAfterInvalidJson() {
        TrackingBody body = new TrackingBody("{\"error\":", false);
        assertEquals("HTTP_500", ContactErrorMapper.fromHttp(Response.error(500, body)).errorCode);
        assertTrue(body.closed);
    }

    /** Có status HTTP rồi thì IOException lúc đọc body vẫn dùng HTTP, không tự suy ra mất mạng. */
    @Test public void bodyReadFailureKeepsHttpFallbackAndClosesBody() {
        TrackingBody body = new TrackingBody("unreadable", true);
        ContactsResult<Boolean> result = ContactErrorMapper.fromHttp(Response.error(502, body));
        assertEquals("HTTP_502", result.errorCode);
        assertFalse(result.isSuccess());
        assertTrue(body.closed);
    }

    /** Không gọi bộ đọc lỗi với phản hồi 2xx vì sẽ tạo lỗi giả cho thao tác thành công. */
    @Test public void rejectsSuccessfulHttpResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> ContactErrorMapper.fromHttp(Response.success("ok")));
    }

    /** Factory lỗi không chấp nhận giá trị có thể bị isSuccess hiểu là thành công. */
    @Test public void errorFactoryRejectsNullAndBlankCodes() {
        for (String code : new String[]{null, "", " \t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> ContactsResult.error(code));
        }
    }

    /** Kết quả thành công và lỗi độc lập, không rò metadata giữa những lần gọi. */
    @Test public void resultFactoriesKeepIndependentStateAndAllowVoidSuccess() {
        ContactsResult<Boolean> error = pending("INCOMING");
        ContactsResult<String> success = ContactsResult.ok("done");
        ContactsResult<Void> noContent = ContactsResult.ok(null);
        assertEquals("done", success.data);
        assertTrue(success.isSuccess());
        assertTrue(noContent.isSuccess());
        assertNull(noContent.data);
        assertNull(success.existingRequestId);
        assertEquals(0, success.retryAfterSeconds);
        assertFalse(error.isSuccess());
    }

    /** Fixture xung đột lời mời dùng chung ID để kiểm hai chiều độc lập. */
    private static ContactsResult<Boolean> pending(String direction) {
        return map(409, "{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{"
                + "\"existingRequestId\":\"" + REQUEST_ID + "\",\"direction\":\"" + direction + "\"}}}");
    }

    /** Chèn trực tiếp giá trị JSON để kiểm kiểu số, không ép thành chuỗi trước khi parse. */
    private static ContactsResult<Boolean> retry(String secondsJson) {
        return map(429, "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{"
                + "\"retryAfterSeconds\":" + secondsJson + "}}}");
    }

    /** Tạo phản hồi lỗi Retrofit trong bộ nhớ, không dùng socket hoặc máy chủ thật. */
    private static ContactsResult<Boolean> map(int status, String json) {
        return ContactErrorMapper.fromHttp(Response.error(status,
                ResponseBody.create(json, MediaType.get("application/json; charset=utf-8"))));
    }

    /** Mọi lỗi thiếu hợp đồng vẫn giữ status, không có dữ liệu thành công hoặc metadata giả. */
    private static void assertFallback(int status, String json) {
        ContactsResult<Boolean> result = map(status, json);
        assertEquals("HTTP_" + status, result.errorCode);
        assertFalse(result.isSuccess());
        assertNull(result.data);
        assertNull(result.existingRequestId);
        assertNull(result.direction);
        assertEquals(0, result.retryAfterSeconds);
    }

    /** Body theo dõi đóng tài nguyên và giả lập IOException tại tầng đọc stream. */
    private static final class TrackingBody extends ResponseBody {
        private boolean closed;
        private final BufferedSource source;

        TrackingBody(String json, boolean failRead) {
            source = Okio.buffer(new ForwardingSource(new Buffer().writeUtf8(json)) {
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    if (failRead) throw new IOException("Không đọc được body giả lập");
                    return super.read(sink, byteCount);
                }
                @Override public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            });
        }

        @Override public MediaType contentType() { return MediaType.get("application/json"); }
        @Override public long contentLength() { return -1; }
        @Override public BufferedSource source() { return source; }
    }
}
