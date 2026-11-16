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

    @Before public void setUp() {
        session = mock(SessionManager.class);
        when(session.getToken()).thenAnswer(i -> token.get());
        when(session.hasToken()).thenAnswer(i -> token.get() != null);
        doAnswer(i -> { token.set(null); return null; }).when(session).clear();
        api = mock(AuthApi.class);
        dao = mock(UserDao.class);
        repo = new RealAuthRepository(api, session, dao, Runnable::run, () -> cacheCleared++);
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
}
