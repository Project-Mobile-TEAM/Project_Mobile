package com.utechat;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.ContactsCache;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.utechat.data.local.entity.*;
import org.junit.*;
import static org.junit.Assert.*;
/** SQLite thật kiểm transaction rollback và bảo toàn dữ liệu hồ sơ khi DTO thiếu trường. */
public class ContactsRoomTest {
    private AppDatabase db;
    @Before public void setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().getTargetContext(),AppDatabase.class).allowMainThreadQueries().build(); }
    @After public void close() { db.close(); }
    private UserEntity user(String name,String phone,boolean privacy) {
        UserEntity u=new UserEntity(); u.setId("B"); u.setDisplayName(name); u.setPhone(phone); u.setAllowMessagesFromNonContacts(privacy); return u;
    }
    @Test public void partialProfilePreservesPhoneAndPrivacy() {
        db.userDao().insert(user("Bob","+84901111111",false));
        db.userDao().upsertPreservingPrivacy(user("Bob mới",null,true));
        UserEntity actual=db.userDao().findById("B"); assertEquals("Bob mới",actual.getDisplayName());
        assertEquals("+84901111111",actual.getPhone()); assertFalse(actual.getAllowMessagesFromNonContacts());
    }
    @Test public void snapshotTransactionRollsBackDeletionOnFailure() {
        ContactEntity c=new ContactEntity(); c.setContactUserId("B"); c.setBlocked(true); db.contactDao().insert(c);
        try { db.runInTransaction(()->{ db.contactDao().clearContacts(); throw new IllegalStateException("test rollback"); }); fail(); }
        catch(IllegalStateException expected) { }
        try(android.database.Cursor cursor=db.getOpenHelper().getReadableDatabase().query("SELECT blocked FROM contact WHERE contactUserId='B'")) {
            assertTrue(cursor.moveToFirst()); assertEquals(1,cursor.getInt(0));
        }
    }

    /** Snapshot mới thay cả hai tập quan hệ nhưng không xóa hồ sơ dùng chung với lịch sử chat. */
    @Test public void snapshotRemovesObsoleteRelationshipsAndRequestsButKeepsProfiles() {
        db.userDao().insert(user("Bob","+84901111111",false));
        db.contactDao().insert(contact("B",true));
        db.contactDao().insertRequest(request("old","A","B","PENDING"));
        new ContactsCache(db).replace(List.of(),List.of(),List.of());
        assertEquals(0,count("contact")); assertEquals(0,count("contact_request"));
        assertNotNull(db.userDao().findById("B"));
    }

    @Test public void blockedRowAndPartialProfileCommitTogether() {
        UserEntity partial=user("Bob",null,false);
        new ContactsCache(db).replace(List.of(partial),List.of(contact("B",true)),List.of());
        assertEquals("Bob",db.userDao().findById("B").getDisplayName());
        assertEquals("B",await(db.contactDao().getBlockedUsers()).get(0).getId());
        assertTrue(await(db.contactDao().getFriends()).isEmpty());
    }

    @Test public void snapshotFailureRestoresOldProfileContactsAndRequests() {
        db.userDao().insert(user("Bob cũ","+84901111111",false));
        db.contactDao().insert(contact("B",true));
        db.contactDao().insertRequest(request("old","A","B","PENDING"));
        ContactRequestEntity invalid=request("invalid","A","B",null);
        assertThrows(android.database.sqlite.SQLiteConstraintException.class,()->new ContactsCache(db).replace(
                List.of(user("Bob mới",null,true)),List.of(contact("C",false)),List.of(invalid)));
        assertEquals("Bob cũ",db.userDao().findById("B").getDisplayName());
        assertEquals("B",await(db.contactDao().getBlockedUsers()).get(0).getId());
        assertEquals("old",await(db.contactDao().getRequests()).get(0).getId());
    }

    @Test public void partialFieldsPreserveKnownProfileWithoutMutatingInput() {
        UserEntity old=user("Bob","+84901111111",false); old.setAvatarUrl("/avatar");
        db.userDao().insert(old);
        UserEntity partial=user(null,null,true);
        db.userDao().upsertPreservingPrivacy(partial);
        UserEntity actual=db.userDao().findById("B");
        assertEquals("Bob",actual.getDisplayName()); assertEquals("/avatar",actual.getAvatarUrl());
        assertEquals("+84901111111",actual.getPhone()); assertFalse(actual.getAllowMessagesFromNonContacts());
        assertNull(partial.getPhone()); assertNull(partial.getDisplayName());
    }

    @Test public void phoneUniquenessFailureRollsBackWholeSnapshot() {
        db.userDao().insert(user("Bob","+84901111111",false));
        db.contactDao().insert(contact("B",true));
        UserEntity other=user("Other","+84901111111",true); other.setId("C");
        assertThrows(android.database.sqlite.SQLiteConstraintException.class,()->new ContactsCache(db).replace(
                List.of(other),List.of(),List.of()));
        assertEquals(1,count("contact")); assertNull(db.userDao().findById("C"));
    }

    @Test public void pendingJoinAndIncomingCountRespectDirectionAndStatus() {
        db.userDao().insert(user("Bob",null,false));
        db.contactDao().insertRequest(request("in","B","A","PENDING"));
        db.contactDao().insertRequest(request("out","A","B","PENDING"));
        db.contactDao().insertRequest(request("closed","B","A","ACCEPTED"));
        assertEquals(2,await(db.contactDao().getPendingRequestsWithUsers()).size());
        assertEquals(Integer.valueOf(1),await(db.contactDao().incomingCount("A")));
        var incoming=await(db.contactDao().getPendingRequestsWithUsers()).stream()
                .filter(r->r.request.getId().equals("in")).findFirst().orElseThrow();
        assertEquals("Bob",incoming.requesterName); assertNull(incoming.addresseeName);
    }

    @Test public void deletingFriendKeepsBlockedRowAndDeletingRequestsIsScoped() {
        db.contactDao().insert(contact("B",true)); db.contactDao().insert(contact("C",false));
        db.contactDao().deleteFriend("B"); db.contactDao().deleteFriend("C");
        assertEquals(1,count("contact"));
        db.contactDao().insertRequest(request("ab","A","B","PENDING"));
        db.contactDao().insertRequest(request("ac","A","C","PENDING"));
        db.contactDao().deleteRequestsFor("B");
        assertEquals("ac",await(db.contactDao().getRequests()).get(0).getId());
    }

    @Test public void schemaRemainsVersionOneWithOnlyIdAndBlockedInContact() {
        try(var c=db.getOpenHelper().getReadableDatabase().query("PRAGMA user_version")) {
            assertTrue(c.moveToFirst()); assertEquals(1,c.getInt(0));
        }
        try(var c=db.getOpenHelper().getReadableDatabase().query("PRAGMA table_info(contact)")) {
            assertEquals(2,c.getCount());
        }
    }

    // Dữ liệu fixture chỉ dành cho SQLite in-memory, không dùng tài khoản hoặc secret thật.
    private ContactEntity contact(String id,boolean blocked) {
        ContactEntity c=new ContactEntity(); c.setContactUserId(id); c.setBlocked(blocked); return c;
    }
    private ContactRequestEntity request(String id,String from,String to,String status) {
        ContactRequestEntity r=new ContactRequestEntity(); r.setId(id); r.setRequesterId(from);
        r.setAddresseeId(to); r.setStatus(status); r.setCreatedAt(1); return r;
    }
    private int count(String table) {
        try(var c=db.getOpenHelper().getReadableDatabase().query("SELECT count(*) FROM "+table)) {
            assertTrue(c.moveToFirst()); return c.getInt(0);
        }
    }
    /** Quan sát LiveData trên main, chờ kết quả Room thật thay vì đọc getValue chưa tải. */
    private <T> T await(LiveData<T> live) {
        CountDownLatch ready=new CountDownLatch(1); AtomicReference<T> value=new AtomicReference<>();
        Observer<T> observer=v->{value.set(v);ready.countDown();};
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(()->live.observeForever(observer));
        try { assertTrue("Room không phát dữ liệu",ready.await(5,TimeUnit.SECONDS)); return value.get(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        finally { instrumentation.runOnMainSync(()->live.removeObserver(observer)); }
    }
}
