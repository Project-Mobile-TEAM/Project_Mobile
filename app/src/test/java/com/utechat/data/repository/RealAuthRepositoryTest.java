package com.utechat.data.repository;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.utechat.data.local.dao.UserDao;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.remote.api.AuthApi;
import com.utechat.data.remote.model.AuthDtos.DeleteAccountRequest;
import com.utechat.data.remote.model.AuthDtos.UserDto;
import com.utechat.data.repository.real.RealAuthRepository;
import com.utechat.data.session.SessionManager;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Kiểm phiên đăng xuất/xoá tài khoản và cache hồ sơ offline; callback mạng do test tự gọi để mô phỏng phản hồi trễ. */
public class RealAuthRepositoryTest {
    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private final AtomicReference<String> token = new AtomicReference<>("old");
    private SessionManager session;
    private AuthApi api;
    private UserDao dao;
    private int cacheCleared;
    private RealAuthRepository repo;
    private final AtomicInteger version = new AtomicInteger();
    private final AtomicReference<String> owner = new AtomicReference<>("u1");
    private Runnable onSessionChanged;

    @Before public void setUp() {
        session = mock(SessionManager.class);
        when(session.getToken()).thenAnswer(i -> token.get());
        when(session.hasToken()).thenAnswer(i -> token.get() != null);
        when(session.getMeId()).thenAnswer(i -> owner.get());
        when(session.versionKey()).thenAnswer(i -> "v"+version.get());
        when(session.sessionKey()).thenAnswer(i -> token.get()==null?null:"v"+version.get()+":"+owner.get());
        doAnswer(i -> { onSessionChanged=i.getArgument(0); return null; }).when(session).setOnSessionChanged(any());
        doAnswer(i -> { token.set(null); owner.set(null); version.incrementAndGet(); onSessionChanged.run(); return null; }).when(session).clear();
        doAnswer(i -> { token.set(i.getArgument(0)); owner.set(i.getArgument(1)); version.incrementAndGet(); onSessionChanged.run(); return null; })
                .when(session).saveAuthenticated(anyString(),anyString(),anyBoolean());
        doAnswer(i -> {
            if(!java.util.Objects.equals(i.getArgument(0),session.sessionKey())||i.getArgument(0)==null) return false;
            ((Runnable)i.getArgument(1)).run(); return true;
        }).when(session).runIfCurrent(nullable(String.class),any());
        api = mock(AuthApi.class);
        dao = mock(UserDao.class);
        repo = new RealAuthRepository(api, session, dao, Runnable::run, () -> cacheCleared++, Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static <T> Callback<T> enqueued(Call<T> call) {
        ArgumentCaptor<Callback<T>> captor = ArgumentCaptor.forClass(Callback.class);
        verify(call).enqueue(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    @Test public void logoutClearsAtOnceAndSendsOldTokenExplicitly() {
        Call<Void> call = mock(Call.class);
        when(api.logout(anyString())).thenReturn(call);
        repo.logout();
        verify(session).clear();
        assertEquals(1, cacheCleared);
        verify(api).logout("Bearer old");
        // Đăng nhập lại trước khi server trả lời: phản hồi trễ của lệnh logout không được xoá phiên mới.
        token.set("new");
        enqueued(call).onResponse(call, Response.success(null));
        assertEquals("new", token.get());
        verify(session, times(1)).clear();
    }

    @SuppressWarnings("unchecked")
    @Test public void profileLoadsFromRoomCacheWhenOffline() {
        when(session.getMeId()).thenReturn("u1");
        when(session.isMeDiscoverable()).thenReturn(false);
        UserEntity cached = new UserEntity();
        cached.setId("u1");
        cached.setPhone("+84912345678");
        cached.setDisplayName("Alice");
        cached.setAllowMessagesFromNonContacts(true);
        when(dao.findById("u1")).thenReturn(cached);
        Call<UserDto> me = mock(Call.class);
        when(api.me()).thenReturn(me);

        UserProfile p = repo.getProfile().getValue();
        enqueued(me).onFailure(me, new java.io.IOException("offline"));
        assertNotNull(p);
        assertEquals("Alice", p.displayName);
        assertFalse(p.discoverable);
        assertTrue(p.allowMessagesFromNonContacts);
    }

    @SuppressWarnings("unchecked")
    @Test public void lateProfileResponseAfterLogoutIsIgnored() {
        Call<UserDto> me = mock(Call.class);
        when(api.me()).thenReturn(me);
        when(api.logout(anyString())).thenReturn(mock(Call.class));
        repo.getProfile();
        repo.logout();
        UserDto dto = new UserDto();
        dto.id = "u1";
        dto.displayName = "Alice";
        enqueued(me).onResponse(me, Response.success(dto));
        assertNull(repo.getProfile().getValue());
        verify(dao, never()).insert(any());
    }

    @SuppressWarnings("unchecked")
    @Test public void freshProfileIsWrittenToRoom() {
        Call<UserDto> me = mock(Call.class);
        when(api.me()).thenReturn(me);
        repo.getProfile();
        UserDto dto = new UserDto();
        dto.id = "u1";
        dto.displayName = "Alice";
        dto.discoverable = true;
        enqueued(me).onResponse(me, Response.success(dto));
        verify(dao).insert(argThat(u -> "u1".equals(u.getId())));
        verify(session).saveMe("u1", true);
        assertEquals("Alice", repo.getProfile().getValue().displayName);
    }

    @SuppressWarnings("unchecked")
    @Test public void deleteAccountClearsSessionOnlyOnSuccess() {
        Call<Void> wrong = mock(Call.class);
        Call<Void> right = mock(Call.class);
        when(api.deleteMe(any(DeleteAccountRequest.class))).thenReturn(wrong, right);

        AuthResult<Boolean>[] out = new AuthResult[1];
        repo.deleteAccount("bad").observeForever(r -> out[0] = r);
        enqueued(wrong).onResponse(wrong, Response.error(400, okhttp3.ResponseBody.create(
                "{\"error\":{\"code\":\"BAD_CREDENTIALS\"}}", okhttp3.MediaType.get("application/json"))));
        assertEquals("BAD_CREDENTIALS", out[0].errorCode);
        assertEquals("old", token.get());

        repo.deleteAccount("good").observeForever(r -> out[0] = r);
        enqueued(right).onResponse(right, Response.success(null));
        assertTrue(out[0].isSuccess());
        assertNull(token.get());
        assertEquals(1, cacheCleared);
    }

    @SuppressWarnings("unchecked")
    @Test public void loginDuringGracePeriodShowsDeletionDateUntilCanceled() {
        Call<UserDto> me = mock(Call.class);
        when(api.me()).thenReturn(me);
        repo.getProfile();
        UserDto dto = new UserDto();
        dto.id = "u1";
        dto.displayName = "Alice";
        dto.deletionScheduledAt = "2026-12-01T00:00:00Z";
        enqueued(me).onResponse(me, Response.success(dto));
        assertEquals("2026-12-01T00:00:00Z", repo.getProfile().getValue().deletionScheduledAt);

        Call<Void> cancel = mock(Call.class);
        when(api.cancelDeletion()).thenReturn(cancel);
        repo.cancelDeletion().observeForever(r -> { });
        enqueued(cancel).onResponse(cancel, Response.success(null));
        assertNull(repo.getProfile().getValue().deletionScheduledAt);
    }

    /** DISK cố ý giữ tác vụ để tái hiện write cũ xếp trước/sau logout và đăng nhập lại cùng actor. */
    @SuppressWarnings("unchecked")
    @Test public void queuedOldProfileCannotWriteAfterSameAccountRelogin() {
        ArrayDeque<Runnable> queue=new ArrayDeque<>();
        repo=new RealAuthRepository(api,session,dao,queue::add,()->cacheCleared++,Runnable::run);
        Call<UserDto> me=mock(Call.class); when(api.me()).thenReturn(me);
        repo.getProfile();
        UserDto dto=new UserDto(); dto.id="u1"; dto.displayName="Old";
        enqueued(me).onResponse(me,Response.success(dto));
        session.saveAuthenticated("old","u1",true);
        while(!queue.isEmpty()) queue.remove().run();
        verify(dao,never()).insert(any());
        assertEquals(1,cacheCleared); assertNull(repo.getCurrentUser().getValue());
    }

    @SuppressWarnings("unchecked")
    @Test public void lateDeleteAfterReloginCannotClearNewSession() {
        Call<Void> deletion=mock(Call.class); when(api.deleteMe(any())).thenReturn(deletion);
        var result=repo.deleteAccount("fixture");
        session.saveAuthenticated("new","u2",true);
        enqueued(deletion).onResponse(deletion,Response.success(null));
        assertEquals("new",session.getToken()); verify(session,never()).clear(); assertNull(result.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test public void lateLoginAfterLogoutCannotRestoreTokenOrWriteCache() {
        Call<com.utechat.data.remote.model.AuthDtos.AuthSuccessResponse> login=mock(Call.class);
        when(api.loginPassword(any())).thenReturn(login); when(api.logout(anyString())).thenReturn(mock(Call.class));
        var result=repo.login("+84901111111","fixture"); repo.logout();
        var dto=new com.utechat.data.remote.model.AuthDtos.AuthSuccessResponse();
        dto.token="stale"; dto.user=new UserDto(); dto.user.id="u1";
        enqueued(login).onResponse(login,Response.success(dto));
        verify(session,never()).saveAuthenticated(anyString(),anyString(),anyBoolean());
        verify(dao,never()).insert(any()); assertNull(result.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test public void oldOfflineFailureDoesNotPublishIntoNewSession() {
        Call<Void> call=mock(Call.class); when(api.changePassword(any())).thenReturn(call);
        var result=repo.changePassword("old","new");
        session.saveAuthenticated("new","u2",true);
        enqueued(call).onFailure(call,new java.io.IOException("offline"));
        assertNull(result.getValue()); assertEquals("new",session.getToken());
    }

    @SuppressWarnings("unchecked")
    @Test public void newLoginQueuesClearBeforeWritingItsProfile() {
        ArrayDeque<Runnable> queue=new ArrayDeque<>(); java.util.List<String> order=new java.util.ArrayList<>();
        repo=new RealAuthRepository(api,session,dao,queue::add,()->order.add("clear"),Runnable::run);
        doAnswer(i->{order.add("write");return null;}).when(dao).insert(any());
        Call<com.utechat.data.remote.model.AuthDtos.AuthSuccessResponse> login=mock(Call.class);
        when(api.loginPassword(any())).thenReturn(login); repo.login("+84901111111","fixture");
        var dto=new com.utechat.data.remote.model.AuthDtos.AuthSuccessResponse();
        dto.token="new"; dto.user=new UserDto(); dto.user.id="u2"; dto.user.displayName="New";
        enqueued(login).onResponse(login,Response.success(dto));
        while(!queue.isEmpty()) queue.remove().run();
        assertEquals(java.util.List.of("clear","write"),order);
        assertEquals("u2",repo.getCurrentUser().getValue().getId());
    }
    /** Giữ callback cache ở main, đổi phiên trước khi phát để kiểm hàng rào tại thời điểm thực thi. */
    @SuppressWarnings("unchecked")
    @Test public void queuedCachedProfileCannotPublishAfterRelogin() {
        ArrayDeque<Runnable> main=new ArrayDeque<>();
        repo=new RealAuthRepository(api,session,dao,Runnable::run,()->cacheCleared++,main::add);
        UserEntity cached=new UserEntity(); cached.setId("u1");
        when(dao.findById("u1")).thenReturn(cached);
        when(api.me()).thenReturn(mock(Call.class));
        repo.getProfile(); assertEquals(1,main.size());
        session.saveAuthenticated("new","u2",true);
        main.remove().run();
        assertNull(repo.getCurrentUser().getValue());
        while(!main.isEmpty()) main.remove().run();
        assertNull(repo.getCurrentUser().getValue());
        verify(dao,never()).insert(any());
    }
}
