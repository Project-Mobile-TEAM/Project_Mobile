package com.utechat.data.remote.model;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.utechat.data.remote.model.ContactDtos.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** JSON fixture theo hợp đồng backend; chạy Gson thật, không phụ thuộc Android hoặc Room. */
public class ContactDtosTest {
    private final Gson gson = new Gson();

    /** Lookup người lạ phải đọc đúng isContact, không nhầm tên trường contact. */
    @Test public void strangerLookupReadsFalseFlagsAndNullPendingFields() {
        Lookup result = gson.fromJson("""
                {"id":"B","displayName":"Người dùng","avatarUrl":null,"isContact":false,
                 "hasPendingRequest":false,"relationshipStatus":"STRANGER",
                 "pendingRequestId":null,"pendingRequestDirection":null}
                """, Lookup.class);
        assertEquals("B", result.id);
        assertEquals("Người dùng", result.displayName);
        assertFalse(result.isContact);
        assertFalse(result.hasPendingRequest);
        assertEquals("STRANGER", result.relationshipStatus);
        assertNull(result.pendingRequestId);
        assertNull(result.pendingRequestDirection);
    }

    /** Boolean true giúp phát hiện lỗi ánh xạ isContact mà giá trị false mặc định có thể che mất. */
    @Test public void friendLookupReadsIsContactTrue() {
        Lookup result = gson.fromJson("""
                {"id":"B","displayName":"Bạn","avatarUrl":"avatar.png","isContact":true,
                 "hasPendingRequest":false,"relationshipStatus":"FRIEND"}
                """, Lookup.class);
        assertTrue(result.isContact);
        assertFalse(result.hasPendingRequest);
        assertEquals("FRIEND", result.relationshipStatus);
        assertEquals("avatar.png", result.avatarUrl);
    }

    /** Lời mời nhận đến cần giữ ID để UI sau này gọi accept/reject đúng lời mời. */
    @Test public void incomingLookupReadsRequestIdAndDirection() {
        Lookup result = gson.fromJson("""
                {"id":"B","isContact":false,"hasPendingRequest":true,
                 "relationshipStatus":"INCOMING_PENDING","pendingRequestId":"request-in",
                 "pendingRequestDirection":"INCOMING"}
                """, Lookup.class);
        assertTrue(result.hasPendingRequest);
        assertEquals("INCOMING_PENDING", result.relationshipStatus);
        assertEquals("request-in", result.pendingRequestId);
        assertEquals("INCOMING", result.pendingRequestDirection);
    }

    /** Chiều gửi đi không được đọc nhầm từ trường direction của thân lỗi 409. */
    @Test public void outgoingLookupReadsRequestIdAndDirection() {
        Lookup result = gson.fromJson("""
                {"id":"B","isContact":false,"hasPendingRequest":true,
                 "relationshipStatus":"OUTGOING_PENDING","pendingRequestId":"request-out",
                 "pendingRequestDirection":"OUTGOING"}
                """, Lookup.class);
        assertEquals("OUTGOING_PENDING", result.relationshipStatus);
        assertEquals("request-out", result.pendingRequestId);
        assertEquals("OUTGOING", result.pendingRequestDirection);
    }

    /** Hồ sơ lồng nhau giữ tên Unicode; model tối thiểu không xuất thêm phone khi mã hóa lại. */
    @Test public void pendingRequestReadsMinimalProfilesAndNullResponseTime() {
        Request result = gson.fromJson("""
                {"id":"request","requester":{"id":"A","displayName":"Người gửi","avatarUrl":null},
                 "addressee":{"id":"B","displayName":"Người nhận","avatarUrl":"avatar.png"},
                 "status":"PENDING","createdAt":"2026-11-18T01:00:00Z","respondedAt":null}
                """, Request.class);
        assertEquals("request", result.id);
        assertEquals("A", result.requester.id);
        assertEquals("Người gửi", result.requester.displayName);
        assertEquals("B", result.addressee.id);
        assertEquals("Người nhận", result.addressee.displayName);
        assertEquals("PENDING", result.status);
        assertNull(result.respondedAt);
        assertFalse(JsonParser.parseString(gson.toJson(result.requester)).getAsJsonObject().has("phone"));
        assertFalse(JsonParser.parseString(gson.toJson(result.addressee)).getAsJsonObject().has("phone"));
    }

    /** DTO giữ nguyên chuỗi thời gian, việc đổi sang millis thuộc mapper của Repository. */
    @Test public void answeredRequestPreservesIsoTimestamps() {
        Request result = gson.fromJson("""
                {"id":"request","status":"ACCEPTED","createdAt":"2026-11-18T01:00:00Z",
                 "respondedAt":"2026-11-18T08:01:00+07:00"}
                """, Request.class);
        assertEquals("ACCEPTED", result.status);
        assertEquals("2026-11-18T01:00:00Z", result.createdAt);
        assertEquals("2026-11-18T08:01:00+07:00", result.respondedAt);
    }

    /** Danh sách có thể chứa phone/avatar null; không biến null thành chuỗi hoặc số giả. */
    @Test public void contactListReadsBlockedBooleanAndNullablePhone() {
        List<Contact> result = gson.fromJson("""
                [{"id":"friend","phone":"+84901111111","displayName":"Bạn","blocked":false,
                  "createdAt":"2026-11-18T01:00:00Z"},
                 {"id":"blocked","phone":null,"displayName":"Người bị chặn","avatarUrl":null,
                  "blocked":true,"createdAt":"2026-11-18T01:00:00Z"}]
                """, new TypeToken<List<Contact>>() { }.getType());
        assertEquals(2, result.size());
        assertEquals("+84901111111", result.get(0).phone);
        assertFalse(result.get(0).blocked);
        assertNull(result.get(1).phone);
        assertNull(result.get(1).avatarUrl);
        assertTrue(result.get(1).blocked);
    }

    /** Request gửi kết bạn chỉ có người nhận, không kèm danh tính người gửi. */
    @Test public void sendBodyContainsOnlyAddresseeId() {
        var body = JsonParser.parseString(gson.toJson(new Send("B"))).getAsJsonObject();
        assertEquals(Set.of("addresseeId"), body.keySet());
        assertEquals("B", body.get("addresseeId").getAsString());
    }

    /** details của lỗi 429 phải giữ thời gian chờ cho thông báo giao diện. */
    @Test public void rateLimitedErrorReadsRetryAfterSeconds() {
        ErrorBody result = gson.fromJson("""
                {"error":{"code":"RATE_LIMITED","message":"Wait","details":{"retryAfterSeconds":60}}}
                """, ErrorBody.class);
        assertEquals("RATE_LIMITED", result.error.code);
        assertEquals(60, result.error.details.retryAfterSeconds);
    }

    /** Lỗi 409 dùng details.direction, đồng thời chấp nhận metadata bổ sung từ server. */
    @Test public void pendingErrorReadsExistingRequestIdAndDirection() {
        ErrorBody result = gson.fromJson("""
                {"error":{"code":"REQUEST_ALREADY_PENDING","details":{"existingRequestId":"request-in",
                 "direction":"INCOMING","requesterId":"B","addresseeId":"A"}}}
                """, ErrorBody.class);
        assertEquals("REQUEST_ALREADY_PENDING", result.error.code);
        assertEquals("request-in", result.error.details.existingRequestId);
        assertEquals("INCOMING", result.error.details.direction);
    }
}
