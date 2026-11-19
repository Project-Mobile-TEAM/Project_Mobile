package com.utechat.data.remote.api;

import static org.junit.Assert.*;

import com.google.gson.stream.MalformedJsonException;
import com.utechat.data.remote.model.ContactDtos;
import com.utechat.data.repository.ContactErrorMapper;
import com.utechat.data.repository.ContactsResult;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** TCP loopback thật cho Retrofit/Gson và mapper; không cần Android, Room, Keystore hoặc PostgreSQL. */
public class ContactTransportTest {
    private ServerSocket server;
    private ExecutorService responder;
    private OkHttpClient client;

    private ContactApi api(String base) {
        client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build();
        return new Retrofit.Builder().baseUrl(base).client(client).addConverterFactory(GsonConverterFactory.create())
                .build().create(ContactApi.class);
    }

    /** Chỉ trả fixture qua socket; token/phiên ứng dụng không được sử dụng trong server unit này. */
    private ContactApi responding(int status, String body) throws IOException {
        // Chỉ dùng java.net/java.io để test vẫn biên dịch với bootclasspath của Android unit test.
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        responder = Executors.newSingleThreadExecutor();
        responder.submit(() -> {
            try (var socket = server.accept()) {
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                do { line = input.readLine(); } while (line != null && !line.isEmpty());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                var output = socket.getOutputStream();
                output.write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(bytes); output.flush();
            } catch (IOException failure) {
                // Đóng server khi test lỗi cũng phải nhả accept, không để executor giữ JVM sống.
                if (!server.isClosed()) throw new java.io.UncheckedIOException(failure);
            }
        });
        return api("http://127.0.0.1:" + server.getLocalPort() + "/");
    }

    @After public void close() throws Exception {
        if (server != null) server.close();
        if (responder != null) { responder.shutdownNow(); assertTrue(responder.awaitTermination(2, TimeUnit.SECONDS)); }
        if (client != null) { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
    }

    @Test public void realTcpSuccessReadsDtoWithoutErrorMapping() throws Exception {
        var response = responding(200, "{\"id\":\"B\",\"displayName\":\"Bạn\",\"isContact\":true,\"relationshipStatus\":\"FRIEND\"}")
                .lookup("+84901111111").execute();
        assertTrue(response.isSuccessful()); assertTrue(response.body().isContact); assertEquals("Bạn", response.body().displayName);
    }

    @Test public void actualConnectionRefusalMapsToNetworkWithoutSessionExpired() throws Exception {
        int port;
        try (var reserve = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = reserve.getLocalPort(); }
        var call = api("http://127.0.0.1:" + port + "/").lookup("+84901111111");
        // Phải thực sự bị từ chối kết nối; Permission denied của sandbox không phải bằng chứng ca này đạt.
        ConnectException failure = assertThrows(ConnectException.class, call::execute);
        ContactsResult<ContactDtos.Lookup> result = ContactErrorMapper.fromFailure(failure);
        assertEquals("NETWORK", result.errorCode); assertFalse(result.isSuccess()); assertNull(result.data); assertFalse(result.refreshRequired);
    }

    @Test public void realHttp401RetainsSessionExpiredEnvelope() throws Exception {
        var response = responding(401, "{\"error\":{\"code\":\"SESSION_EXPIRED\"}}").lookup("+84901111111").execute();
        assertEquals("SESSION_EXPIRED", ContactErrorMapper.fromHttp(response).errorCode);
    }

    @Test public void realHttp429RetainsRetryMetadata() throws Exception {
        var response = responding(429, "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":7}}}")
                .lookup("+84901111111").execute();
        var result = ContactErrorMapper.fromHttp(response); assertEquals("RATE_LIMITED", result.errorCode); assertEquals(7, result.retryAfterSeconds);
    }

    @Test public void realProxyHtmlErrorUsesHttpFallback() throws Exception {
        var response = responding(503, "<html>Unavailable</html>").lookup("+84901111111").execute();
        assertEquals("HTTP_503", ContactErrorMapper.fromHttp(response).errorCode);
    }

    @Test public void wrongJsonShapeIsInvalidResponseRatherThanNetwork() throws Exception {
        var call = responding(200, "[]").lookup("+84901111111");
        Exception failure = assertThrows(Exception.class, call::execute);
        assertEquals("INVALID_RESPONSE", ContactErrorMapper.fromFailure(failure).errorCode);
    }

    @Test public void malformedJsonIoIsNotClassifiedAsOffline() {
        assertEquals("INVALID_RESPONSE", ContactErrorMapper.fromFailure(new MalformedJsonException("fixture")).errorCode);
    }

    @Test public void tlsFailureRemainsNetworkAndDoesNotBecomeAuthError() {
        assertEquals("NETWORK", ContactErrorMapper.fromFailure(new SSLHandshakeException("fixture")).errorCode);
    }

    @Test public void exceptionMessagesAreNotCopiedIntoPublicErrors() {
        var result = ContactErrorMapper.fromFailure(new IOException("fixture token=not-a-real-secret"));
        assertEquals("NETWORK", result.errorCode); assertNull(result.existingRequestId); assertNull(result.direction); assertEquals(0, result.retryAfterSeconds);
    }

    @Test public void missingFailureCauseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContactErrorMapper.fromFailure(null));
    }
}
