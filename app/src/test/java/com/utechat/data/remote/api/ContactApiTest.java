package com.utechat.data.remote.api;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utechat.data.remote.model.ContactDtos;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Chạy Retrofit/Gson thật; interceptor trả fixture trước tầng socket, không cần backend. */
public class ContactApiTest {
    private static final String USER_ID = "22222222-2222-4222-8222-222222222222";
    private static final String REQUEST_ID = "33333333-3333-4333-8333-333333333333";
    private static final String REQUEST_JSON = """
            {"id":"33333333-3333-4333-8333-333333333333","status":"PENDING",
             "requester":{"id":"A","displayName":"Người gửi"},
             "addressee":{"id":"22222222-2222-4222-8222-222222222222"},
             "createdAt":"2026-11-18T00:00:00Z","respondedAt":null}
            """;
    private OkHttpClient client;
    private ContactApi api;
    private Request sent;
    private int status;
    private String json;
    private IOException networkFailure;

    /** Domain và prefix chỉ là fixture; interceptor chặn request nên không phát sinh DNS/mạng. */
    @Before public void setUp() {
        status = 200;
        json = "{}";
        client = new OkHttpClient.Builder().addInterceptor(chain -> {
            sent = chain.request();
            if (networkFailure != null) throw networkFailure;
            return new okhttp3.Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Fixture")
                    .body(ResponseBody.create(json, MediaType.get("application/json; charset=utf-8")))
                    .build();
        }).build();
        api = new Retrofit.Builder().baseUrl("https://example.test/base/").client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(ContactApi.class);
    }

    /** Giải phóng tài nguyên client kể cả khi một assertion thất bại. */
    @After public void tearDown() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }

    /** Số E.164 phải giữ dấu + và chỉ xuất hiện một lần trong query phone. */
    @Test public void lookupEncodesPhoneAndConvertsRelationship() throws IOException {
        json = """
                {"id":"B","displayName":"Bạn","isContact":true,"hasPendingRequest":false,
                 "relationshipStatus":"FRIEND","pendingRequestId":null}
                """;
        Response<ContactDtos.Lookup> result = api.lookup("+84901111111").execute();
        assertEquals(200, result.code());
        assertRequest("GET", "/base/api/users/lookup");
        assertEquals("phone=%2B84901111111", sent.url().encodedQuery());
        assertEquals("+84901111111", sent.url().queryParameter("phone"));
        assertEquals(Set.of("phone"), sent.url().queryParameterNames());
        assertNull(sent.body());
        assertTrue(result.body().isContact);
        assertEquals("FRIEND", result.body().relationshipStatus);
        assertEquals("Bạn", result.body().displayName);
    }

    /** POST tạo lời mời gửi JSON chỉ có addresseeId, không tự khai người gửi. */
    @Test public void sendPostsOnlyAddresseeAndReadsCreatedRequest() throws IOException {
        status = 201;
        json = REQUEST_JSON;
        Response<ContactDtos.Request> result = api.send(new ContactDtos.Send(USER_ID)).execute();
        assertEquals(201, result.code());
        assertRequest("POST", "/base/api/contact-requests");
        assertNull(sent.url().query());
        Buffer buffer = new Buffer();
        sent.body().writeTo(buffer);
        JsonObject body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
        assertEquals(Set.of("addresseeId"), body.keySet());
        assertEquals(USER_ID, body.get("addresseeId").getAsString());
        assertEquals("application/json; charset=UTF-8", sent.body().contentType().toString());
        assertEquals(REQUEST_ID, result.body().id);
        assertEquals("Người gửi", result.body().requester.displayName);
        assertNull(result.body().respondedAt);
    }

    /** Danh sách dùng kiểu List<Request>, không bị Gson chuyển thành Map không định kiểu. */
    @Test public void requestsReadsTypedListWithoutImplicitFilters() throws IOException {
        json = "[" + REQUEST_JSON + "]";
        Response<List<ContactDtos.Request>> result = api.requests().execute();
        assertRequest("GET", "/base/api/contact-requests");
        assertNull(sent.url().query());
        assertNull(sent.body());
        assertEquals(1, result.body().size());
        assertEquals(REQUEST_ID, result.body().get(0).id);
        assertEquals(USER_ID, result.body().get(0).addressee.id);
    }

    /** Accept đặt ID lời mời ở path và đọc DTO trạng thái sau khi server phản hồi. */
    @Test public void acceptPostsToRequestAction() throws IOException {
        json = REQUEST_JSON.replace("PENDING", "ACCEPTED");
        Response<ContactDtos.Request> result = api.accept(REQUEST_ID).execute();
        assertRequest("POST", "/base/api/contact-requests/" + REQUEST_ID + "/accept");
        assertEquals(0, sent.body().contentLength());
        assertEquals("ACCEPTED", result.body().status);
    }

    /** Reject là POST action riêng, không dùng nhầm hủy lời mời của người gửi. */
    @Test public void rejectPostsToRequestAction() throws IOException {
        json = REQUEST_JSON.replace("PENDING", "REJECTED");
        Response<ContactDtos.Request> result = api.reject(REQUEST_ID).execute();
        assertRequest("POST", "/base/api/contact-requests/" + REQUEST_ID + "/reject");
        assertEquals(0, sent.body().contentLength());
        assertEquals("REJECTED", result.body().status);
    }

    /** Hủy lời mời dùng DELETE và chấp nhận 204 không có JSON. */
    @Test public void cancelDeletesRequestAndAcceptsNoContent() throws IOException {
        status = 204;
        json = "";
        assertNoContent(api.cancel(REQUEST_ID), "DELETE", "/base/api/contact-requests/" + REQUEST_ID);
    }

    /** Danh sách bạn đọc phone nullable và cờ blocked từ JSON contact. */
    @Test public void friendsReadsContactList() throws IOException {
        json = "[{\"id\":\"B\",\"displayName\":\"Bạn\",\"phone\":null,\"blocked\":false}]";
        Response<List<ContactDtos.Contact>> result = api.friends().execute();
        assertRequest("GET", "/base/api/contacts");
        assertEquals(1, result.body().size());
        assertEquals("Bạn", result.body().get(0).displayName);
        assertFalse(result.body().get(0).blocked);
        assertNull(result.body().get(0).phone);
    }

    /** Danh sách chặn gọi đường dẫn riêng và vẫn giữ blocked=true. */
    @Test public void blockedReadsSeparateContactList() throws IOException {
        json = "[{\"id\":\"B\",\"blocked\":true}]";
        Response<List<ContactDtos.Contact>> result = api.blocked().execute();
        assertRequest("GET", "/base/api/contacts/blocked");
        assertTrue(result.body().get(0).blocked);
    }

    /** Hủy kết bạn truyền ID người dùng và không đòi DTO trong phản hồi 204. */
    @Test public void unfriendDeletesUserRelationship() throws IOException {
        status = 204;
        json = "";
        assertNoContent(api.unfriend(USER_ID), "DELETE", "/base/api/contacts/" + USER_ID);
    }

    /** Chặn là POST tới tài nguyên block, không gửi body hồ sơ hoặc danh tính actor. */
    @Test public void blockPostsUserBlock() throws IOException {
        status = 204;
        json = "";
        assertNoContent(api.block(USER_ID), "POST", "/base/api/contacts/" + USER_ID + "/block");
        assertEquals(0, sent.body().contentLength());
    }

    /** Bỏ chặn là DELETE tài nguyên block, khác hủy quan hệ bạn bè. */
    @Test public void unblockDeletesUserBlock() throws IOException {
        status = 204;
        json = "";
        assertNoContent(api.unblock(USER_ID), "DELETE", "/base/api/contacts/" + USER_ID + "/block");
    }

    /** Path mặc định mã hóa slash/khoảng trắng; client không chèn thêm segment từ ID. */
    @Test public void pathParameterIsEncodedAsSingleSegment() throws IOException {
        json = REQUEST_JSON;
        api.accept("request/with space").execute();
        assertRequest("POST", "/base/api/contact-requests/request%2Fwith%20space/accept");
    }

    /** Retrofit giữ nguyên HTTP 429 và thân lỗi cho lớp ánh xạ lỗi xử lý về sau. */
    @Test public void rateLimitKeepsStatusAndRawErrorBody() throws IOException {
        status = 429;
        json = "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":12}}}";
        Response<ContactDtos.Lookup> result = api.lookup("+84901111111").execute();
        assertFalse(result.isSuccessful());
        assertEquals(429, result.code());
        assertNull(result.body());
        assertEquals(json, result.errorBody().string());
    }

    /** Lỗi gửi trùng giữ details ID/chiều lời mời, không đọc nhầm thành DTO thành công. */
    @Test public void pendingConflictKeepsStatusAndRawErrorBody() throws IOException {
        status = 409;
        json = "{\"error\":{\"code\":\"REQUEST_ALREADY_PENDING\",\"details\":{"
                + "\"existingRequestId\":\"" + REQUEST_ID + "\",\"direction\":\"INCOMING\"}}}";
        Response<ContactDtos.Request> result = api.send(new ContactDtos.Send(USER_ID)).execute();
        assertFalse(result.isSuccessful());
        assertEquals(409, result.code());
        assertNull(result.body());
        assertEquals(json, result.errorBody().string());
    }

    /** Mảng JSON rỗng là danh sách rỗng hợp lệ, khác phản hồi không có body. */
    @Test public void emptyFriendsIsSuccessfulEmptyList() throws IOException {
        json = "[]";
        Response<List<ContactDtos.Contact>> result = api.friends().execute();
        assertTrue(result.isSuccessful());
        assertNotNull(result.body());
        assertTrue(result.body().isEmpty());
    }

    /** Lỗi transport phải nổi lên dưới dạng IOException để phân biệt với HTTP 4xx. */
    @Test public void transportFailurePropagatesInsteadOfReturningSuccess() {
        networkFailure = new IOException("Mất kết nối giả lập");
        IOException failure = assertThrows(IOException.class, () -> api.friends().execute());
        assertSame(networkFailure, failure);
        assertRequest("GET", "/base/api/contacts");
    }

    /** Kiểm request sau khi Retrofit xử lý annotation, gồm cả base URL không bị thay thế. */
    private void assertRequest(String method, String encodedPath) {
        assertNotNull(sent);
        assertEquals(method, sent.method());
        assertEquals("https", sent.url().scheme());
        assertEquals("example.test", sent.url().host());
        assertEquals(encodedPath, sent.url().encodedPath());
    }

    /** Dùng chung assertion cho các thao tác không có nội dung trả về. */
    private void assertNoContent(Call<Void> call, String method, String path) throws IOException {
        Response<Void> result = call.execute();
        assertTrue(result.isSuccessful());
        assertEquals(204, result.code());
        assertNull(result.body());
        assertRequest(method, path);
        assertNull(sent.url().query());
        if ("DELETE".equals(method)) assertNull(sent.body());
    }
}
