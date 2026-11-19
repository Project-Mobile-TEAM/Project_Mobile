package com.utechat.backend;

import static org.junit.jupiter.api.Assertions.*;
import static com.utechat.backend.support.AndroidContactClient.field;

import com.utechat.backend.security.JwtService;
import com.utechat.backend.support.AndroidContactClient;
import com.utechat.backend.support.PostgresFixture;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import retrofit2.Response;

/** Mục 10: source Retrofit/Gson của Android gọi Tomcat/JWT và PostgreSQL thật; không giả response backend. */
@Timeout(90)
class AndroidContactContractTest {
    @Test void androidJvmContractSuitesPassWithoutIgnoredTests() throws Exception {
        try (var client = new AndroidContactClient()) {
            var result = client.runUnitContracts();
            System.out.println("ANDROID_CONTRACT_JUNIT4 tests=" + result.getRunCount() + " failures=" + result.getFailureCount()
                    + " ignored=" + result.getIgnoreCount());
            assertEquals(58, result.getRunCount()); assertEquals(0, result.getIgnoreCount());
            assertTrue(result.wasSuccessful(), () -> result.getFailures().toString());
        }
    }

    /** Gọi đủ 11 endpoint bằng DTO client thật, đồng thời đọc lỗi 401/404/409/429 qua mapper Android. */
    @Test void retrofitUsesAllContactEndpointsOverRealJwtHttpAndPostgres() throws Exception {
        try (var db = new PostgresFixture(); var client = new AndroidContactClient()) {
            var args = new ArrayList<>(Arrays.asList(LocalDevServer.databaseArguments(db.source.getUrl(), db.source.getUsername(), db.source.getPassword())));
            args.addAll(List.of("--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=dev",
                    "--jwt.secret=phase4-android-contract-test-key-not-production"));
            var context = (ServletWebServerApplicationContext) SpringApplication.run(BackendApplication.class, args.toArray(String[]::new));
            try {
                UUID a = UUID.randomUUID(), b = UUID.randomUUID();
                for (UUID id : List.of(a, b)) {
                    db.jdbc.update("INSERT INTO users(id,phone_e164,display_name,password_hash) VALUES (?,?,?,'test')", id,
                            id.equals(a) ? "+84901111111" : "+84902222222", id.equals(a) ? "Người A" : "Người B");
                    db.jdbc.update("INSERT INTO user_devices(user_id,device_id_hash,token_valid_after) VALUES (?,'test-device','2000-01-01T00:00:00Z')", id);
                }
                String base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/";
                var jwt = context.getBean(JwtService.class);
                Object apiA = client.api(base, jwt.issueAccessToken(a, "test-device"));
                Object apiB = client.api(base, jwt.issueAccessToken(b, "test-device"));
                Object stranger = body(client.call(apiA, "lookup", "+84902222222"), 200);
                assertEquals("STRANGER", field(stranger, "relationshipStatus")); assertEquals(false, field(stranger, "isContact"));
                assertEquals("Người B", field(stranger, "displayName"));
                Object sent = body(client.call(apiA, "send", client.sendBody(b)), 201); String key = (String) field(sent, "id");
                assertEquals(a.toString(), field(field(sent, "requester"), "id"));
                assertEquals(key, field(((List<?>) body(client.call(apiB, "requests"), 200)).get(0), "id"));
                Object conflict = client.httpError(client.call(apiA, "send", client.sendBody(b)));
                assertEquals("REQUEST_ALREADY_PENDING", field(conflict, "errorCode"));
                assertEquals(key, field(conflict, "existingRequestId")); assertEquals("OUTGOING", field(conflict, "direction"));
                Object accepted = body(client.call(apiB, "accept", key), 200); assertEquals("ACCEPTED", field(accepted, "status"));
                assertNotNull(field(accepted, "respondedAt"));
                Object friend = ((List<?>) body(client.call(apiA, "friends"), 200)).get(0);
                assertEquals(b.toString(), field(friend, "id")); assertEquals("+84902222222", field(friend, "phone"));
                body(client.call(apiA, "unfriend", b.toString()), 204);
                assertTrue(((List<?>) body(client.call(apiA, "friends"), 200)).isEmpty());
                String reject = (String) field(body(client.call(apiA, "send", client.sendBody(b)), 201), "id");
                assertEquals("REJECTED", field(body(client.call(apiB, "reject", reject), 200), "status"));
                String cancel = (String) field(body(client.call(apiB, "send", client.sendBody(a)), 201), "id");
                body(client.call(apiB, "cancel", cancel), 204);
                body(client.call(apiA, "block", b.toString()), 204);
                Object blocked = ((List<?>) body(client.call(apiA, "blocked"), 200)).get(0);
                assertEquals(true, field(blocked, "blocked")); assertNull(field(blocked, "phone"));
                assertEquals("NOT_FOUND", field(client.httpError(client.call(apiB, "lookup", "+84901111111")), "errorCode"));
                body(client.call(apiA, "unblock", b.toString()), 204);
                assertTrue(((List<?>) body(client.call(apiA, "blocked"), 200)).isEmpty());
                assertTrue(((List<?>) body(client.call(apiA, "requests"), 200)).isEmpty());
                Object anonymous = client.api(base, null);
                assertEquals("SESSION_EXPIRED", field(client.httpError(client.call(anonymous, "friends")), "errorCode"));
                boolean limited = false;
                for (int i = 0; i < 11; i++) {
                    var response = client.call(apiA, "lookup", "+84902222222");
                    if (response.code() == 429) {
                        Object error = client.httpError(response); assertEquals("RATE_LIMITED", field(error, "errorCode"));
                        assertTrue((Integer) field(error, "retryAfterSeconds") > 0); limited = true; break;
                    }
                    body(response, 200);
                }
                assertTrue(limited, "Client phải đọc được 429 thật từ backend");
                // Đóng server để kiểm mất kết nối thật; lỗi vận chuyển không được đổi thành SESSION_EXPIRED.
                context.close();
                IOException failure = assertThrows(IOException.class, () -> client.call(apiA, "lookup", "+84902222222"));
                assertEquals("NETWORK", field(client.networkError(failure), "errorCode"));
            } finally { context.close(); }
        }
    }

    private static Object body(Response<?> response, int status) {
        assertEquals(status, response.code());
        if (status == 204) assertNull(response.body()); else assertNotNull(response.body());
        return response.body();
    }
}
