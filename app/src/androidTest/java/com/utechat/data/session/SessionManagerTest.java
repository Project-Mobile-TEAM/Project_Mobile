package com.utechat.data.session;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.runner.RunWith;

/** Kiểm thử trên thiết bị vì Keystore không chạy được trên JVM: token được mã hoá, đọc lại đúng và xoá sạch. */
@RunWith(AndroidJUnit4.class)
public class SessionManagerTest {
    @Before public void isolateSession() {
        SessionManager.init(ApplicationProvider.getApplicationContext());
        SessionManager.get().setOnSessionChanged(()->{});
        SessionManager.get().clear();
    }
    @After public void cleanup() {
        SessionManager.get().setOnSessionChanged(()->{});
        SessionManager.get().clear();
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(SessionEvents::consume);
    }
    @Test public void tokenRoundTripsEncryptedAndClears() {
        Context context = ApplicationProvider.getApplicationContext();
        SessionManager.init(context);
        SessionManager session = SessionManager.get();
        session.saveToken("header.payload.signature");
        assertEquals("header.payload.signature", session.getToken());
        // Ổ đĩa chỉ chứa bản mã hoá, không có chuỗi token gốc.
        String stored = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("token", null);
        assertNotNull(stored);
        assertFalse(stored.contains("payload"));
        session.clear();
        assertNull(session.getToken());
        assertNull(context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("token", null));
    }

    @Test public void deviceIdIsGeneratedOnceAndKeptAfterLogout() {
        SessionManager.init(ApplicationProvider.getApplicationContext());
        SessionManager session = SessionManager.get();
        String first = session.getDeviceId();
        assertEquals(36, first.length()); // UUID
        session.clear();
        assertEquals(first, session.getDeviceId());
    }

    @Test public void sameActorAndTokenReloginStillChangesKeyWithoutLeakingToken() {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        String old=session.sessionKey(); session.clear(); session.saveAuthenticated("fixture-token","A",true);
        assertNotEquals(old,session.sessionKey()); assertFalse(session.sessionKey().contains("fixture-token"));
    }
    @Test public void actorChangeInvalidatesPreviouslyCapturedKey() {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        String key=session.sessionKey(); session.saveMe("B",false);
        assertEquals("B",session.getCurrentUserId()); assertNotEquals(key,session.sessionKey());
    }
    @Test public void guardRejectsNullLoggedOutAndPreviousKeys() {
        var session=SessionManager.get(); assertFalse(session.runIfCurrent(null,()->fail()));
        session.saveAuthenticated("fixture-token","A",true); String key=session.sessionKey();
        AtomicInteger writes=new AtomicInteger(); assertTrue(session.runIfCurrent(key,writes::incrementAndGet));
        session.clear(); assertFalse(session.runIfCurrent(key,writes::incrementAndGet)); assertEquals(1,writes.get());
    }
    /** Khóa phiên bao trọn transaction, không có khoảng check-then-write khi logout chạy đồng thời. */
    @Test public void logoutWaitsForRunningGuardedWrite() throws Exception {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        String key=session.sessionKey(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CountDownLatch clearStarted=new CountDownLatch(1),cleared=new CountDownLatch(1);
        Thread writer=new Thread(()->session.runIfCurrent(key,()->{
            entered.countDown(); try { if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
            catch(InterruptedException e) { throw new AssertionError(e); }
        }));
        Thread logout=new Thread(()->{clearStarted.countDown();session.clear();cleared.countDown();});
        writer.start();
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS)); logout.start(); assertTrue(clearStarted.await(2,TimeUnit.SECONDS));
            assertFalse(cleared.await(100,TimeUnit.MILLISECONDS));
        } finally { release.countDown(); writer.join(3000); logout.join(3000); }
        assertFalse(writer.isAlive()); assertFalse(logout.isAlive()); assertNull(session.sessionKey());
    }
    @Test public void old401CannotClearReloginWithIdenticalToken() {
        var session=SessionManager.get(); session.saveAuthenticated("same-token","A",true);
        String version=session.versionKey(); session.saveAuthenticated("same-token","A",true);
        assertFalse(session.clearIfCurrent("same-token",version)); assertTrue(session.hasToken());
        assertTrue(session.clearIfCurrent("same-token",session.versionKey())); assertNull(session.sessionKey());
    }
    @Test public void everySessionTransitionNotifiesCacheInvalidator() {
        var session=SessionManager.get(); AtomicInteger clears=new AtomicInteger();
        session.setOnSessionChanged(clears::incrementAndGet); int initial=clears.get();
        session.saveAuthenticated("fixture-token","A",true); session.saveMe("A",false);
        assertEquals(initial+1,clears.get()); session.clear(); assertEquals(initial+2,clears.get());
    }
    @Test public void freshInstanceReadsEncryptedIdentityAfterProcessRestart() throws Exception {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",false);
        var constructor=SessionManager.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        SessionManager restored=constructor.newInstance((Context)ApplicationProvider.getApplicationContext());
        assertEquals("fixture-token",restored.getToken()); assertEquals("A",restored.getCurrentUserId());
        assertFalse(restored.isMeDiscoverable());
    }
    @Test public void queuedExpirationEventDoesNotMatchNewLogin() {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true); session.clear();
        SessionEvents.notifyExpired(session.versionKey()); assertTrue(SessionEvents.isCurrentExpiration());
        session.saveAuthenticated("fixture-token","A",true); assertFalse(SessionEvents.isCurrentExpiration());
    }

    /** Response fixture chạy qua AuthInterceptor thật; không dùng backend hoặc JWT người dùng. */
    private okhttp3.OkHttpClient client(okhttp3.Interceptor response) {
        return new okhttp3.OkHttpClient.Builder().addInterceptor(new com.utechat.data.remote.api.AuthInterceptor())
                .addInterceptor(response).build();
    }
    private okhttp3.Request request() {
        return new okhttp3.Request.Builder().url(com.utechat.BuildConfig.BACKEND_BASE_URL+"api/contacts").build();
    }
    private okhttp3.Response unauthorized(okhttp3.Request request) {
        return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(401)
                .message("fixture").body(okhttp3.ResponseBody.create("",okhttp3.MediaType.get("application/json"))).build();
    }
    @Test public void current401InvalidatesCacheWithoutActivity() throws Exception {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        AtomicInteger clears=new AtomicInteger(); session.setOnSessionChanged(clears::incrementAndGet);
        var client=client(chain->{assertEquals("Bearer fixture-token",chain.request().header("Authorization"));return unauthorized(chain.request());});
        try(var response=client.newCall(request()).execute()) { assertEquals(401,response.code()); }
        assertNull(session.getToken()); assertEquals(1,clears.get());
    }
    @Test public void late401WithSameTokenCannotInvalidateNewLogin() throws Exception {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        var client=client(chain->{session.saveAuthenticated("fixture-token","A",true);return unauthorized(chain.request());});
        try(var response=client.newCall(request()).execute()) { assertEquals(401,response.code()); }
        assertEquals("fixture-token",session.getToken()); assertEquals("A",session.getCurrentUserId());
    }
    @Test public void transportFailureKeepsTokenAndCache() {
        var session=SessionManager.get(); session.saveAuthenticated("fixture-token","A",true);
        AtomicInteger clears=new AtomicInteger(); session.setOnSessionChanged(clears::incrementAndGet);
        var client=client(chain->{throw new java.io.IOException("fixture offline");});
        assertThrows(java.io.IOException.class,()->client.newCall(request()).execute());
        assertTrue(session.hasToken()); assertEquals(0,clears.get());
    }
    @Test public void explicitLogoutHeader401DoesNotClearCurrentSession() throws Exception {
        var session=SessionManager.get(); session.saveAuthenticated("new-token","A",true);
        var client=client(chain->unauthorized(chain.request()));
        try(var response=client.newCall(request().newBuilder().header("Authorization","Bearer old-token").build()).execute()) {
            assertEquals(401,response.code());
        }
        assertEquals("new-token",session.getToken());
    }
}
