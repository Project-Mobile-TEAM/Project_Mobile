package com.utechat.data.remote.api;

import com.utechat.data.remote.model.ChatDtos;
import java.io.IOException;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Kiểm tra request thực do Retrofit tạo mà không cần gửi mạng. */
public class Phase7ChatApiTest {
    @Test
    public void removingReactionUsesExplicitNullAndToggleCannotReplay() throws IOException {
        ChatApi api = ApiClient.getRetrofit().create(ChatApi.class);
        Request removal = api.updateReaction("Bearer old-session", "conversation", 12,
                new ChatDtos.ReactionRequest(null)).request();
        assertEquals("PUT", removal.method());
        assertEquals("Bearer old-session", removal.header("Authorization"));
        assertEquals("{\"emoji\":null}", bodyText(removal));

        // Lặp một PUT có cùng emoji sẽ đảo kết quả, nên request phải đánh dấu body một lần.
        Request change = api.updateReaction("Bearer old-session", "conversation", 12,
                new ChatDtos.ReactionRequest("👍")).request();
        assertEquals("{\"emoji\":\"👍\"}", bodyText(change));
        assertTrue(change.body().isOneShot());
    }

    private String bodyText(Request request) throws IOException {
        RequestBody body = request.body();
        assertNotNull(body);
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }
}
