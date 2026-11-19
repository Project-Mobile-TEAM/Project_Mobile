package com.utechat.data.repository;

import static org.junit.Assert.*;
import androidx.lifecycle.*;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.Gson;
import com.utechat.data.local.AppDatabase;
import com.utechat.data.local.entity.*;
import com.utechat.data.remote.api.ContactApi;
import com.utechat.data.remote.model.ContactDtos.*;
import com.utechat.data.remote.model.ContactDtos.Request;
import com.utechat.data.repository.real.RealContactsRepository;
import com.utechat.data.session.SessionManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import okhttp3.*;
import org.junit.*;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Mục 12–13: Repository/Retrofit/Gson đi tới Room thật; một ca thêm TCP loopback thật. */
public class ContactsRepositoryRoomTest {
    private AppDatabase db;
    private SessionManager session;
    private RealContactsRepository repo;
    private final ArrayDeque<Reply> replies=new ArrayDeque<>();
    private final List<String> calls=new ArrayList<>();
    private final List<OkHttpClient> clients=new ArrayList<>();
    private final Gson gson=new Gson();
    private record Reply(int code,String json,boolean offline) { }
    private static class Queue implements Executor {
        final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
        public void execute(Runnable action) { jobs.add(action); }
        void drain() { while(!jobs.isEmpty()) jobs.remove().run(); }
    }
    @Before public void setup() {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        SessionManager.init(context); session=SessionManager.get(); session.setOnSessionChanged(()->{});
        session.clear(); session.saveAuthenticated("repository-test-token","A",true);
        session.setOnSessionChanged(db::clearAllTables);
        OkHttpClient client=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{
            calls.add(chain.request().url().encodedPath()); Reply reply=replies.remove();
            if(reply.offline()) throw new IOException("fixture lost response");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.code())
                    .message("fixture").body(ResponseBody.create(reply.json(),MediaType.get("application/json"))).build();
        }).build();
        clients.add(client); repo=repository(api(client,"http://127.0.0.1/"),Runnable::run);
    }
    @After public void cleanup() {
        session.setOnSessionChanged(()->{}); session.clear();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        db.close();
        for(OkHttpClient client:clients) { client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow(); }
    }
    private ContactApi api(OkHttpClient client,String base) {
        return new Retrofit.Builder().baseUrl(base).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(ContactApi.class);
    }
    private RealContactsRepository repository(ContactApi api,Executor disk) {
        return new RealContactsRepository(db.contactDao(),db.userDao(),api,Runnable::run,disk,session,db::runInTransaction);
    }
    private void reply(int code,Object body) { replies.add(new Reply(code,code==204?"":gson.toJson(body),false)); }
    private void snapshot(List<Contact> friends,List<Contact> blocked,List<Request> requests) {
        reply(200,friends);reply(200,blocked);reply(200,requests);
    }
    private static Contact contact(String id,boolean blocked) {
        Contact c=new Contact();c.id=id;c.displayName="User "+id;c.blocked=blocked;return c;
    }
    private static User user(String id) { User u=new User();u.id=id;u.displayName="User "+id;return u; }
    private static Request request(String status) {
        Request r=new Request();r.id="r1";r.requester=user("A");r.addressee=user("B");r.status=status;
        r.createdAt="2026-11-19T00:00:00Z";return r;
    }
    private void cache(String id,boolean blocked) {
        UserEntity u=new UserEntity();u.setId(id);u.setDisplayName("Cached "+id);db.userDao().insert(u);
        ContactEntity c=new ContactEntity();c.setContactUserId(id);c.setBlocked(blocked);db.contactDao().insert(c);
    }
    private int count(String table) {
        try(var cursor=db.getOpenHelper().getReadableDatabase().query("SELECT count(*) FROM "+table)) {
            assertTrue(cursor.moveToFirst());return cursor.getInt(0);
        }
    }
    private boolean blocked(String id) {
        try(var c=db.getOpenHelper().getReadableDatabase().query("SELECT blocked FROM contact WHERE contactUserId=?",new Object[]{id})) {
            assertTrue(c.moveToFirst());return c.getInt(0)==1;
        }
    }
    /** Chờ giá trị thật, không nhận nhầm giá trị rỗng khởi tạo của MediatorLiveData. */
    private <T> T await(LiveData<T> live,Predicate<T> accept) {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<T> value=new AtomicReference<>();
        Observer<T> observer=v->{if(v!=null&&accept.test(v)){value.set(v);done.countDown();}};
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(()->live.observeForever(observer));
        try { assertTrue("Không nhận kết quả Repository",done.await(5,TimeUnit.SECONDS));return value.get(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        finally{instrumentation.runOnMainSync(()->live.removeObserver(observer));}
    }
    private <T> ContactsResult<T> result(LiveData<ContactsResult<T>> live) { return await(live,r->true); }

    // Đối soát thực sự xóa dòng cũ, giữ profile thiếu trường và không nới quyền riêng tư.
    @Test public void fullRefreshReconcilesRoomAndPreservesKnownProfile() {
        cache("Z",true);cache("B",false);
        UserEntity old=db.userDao().findById("B");old.setPhone("+84901111111");old.setAllowMessagesFromNonContacts(false);db.userDao().insert(old);
        snapshot(List.of(contact("B",false)),List.of(contact("C",true)),List.of(request("PENDING")));
        assertTrue(result(repo.refresh()).isSuccess());assertEquals(2,count("contact"));assertEquals(1,count("contact_request"));
        assertTrue(blocked("C"));assertFalse(blocked("B"));assertNotNull(db.userDao().findById("Z"));
        assertEquals("+84901111111",db.userDao().findById("B").getPhone());assertFalse(db.userDao().findById("B").getAllowMessagesFromNonContacts());
    }
    @Test public void partialHttpFailureLeavesExistingRoomSnapshotUntouched() {
        cache("Z",true);reply(200,List.of(contact("B",false)));reply(503,Map.of("error",Map.of("code","UNAVAILABLE")));
        assertEquals("UNAVAILABLE",result(repo.refresh()).errorCode);assertEquals(1,count("contact"));assertTrue(blocked("Z"));
    }
    @Test public void invalidSnapshotDoesNotDeleteRoomData() {
        cache("Z",true);snapshot(List.of(contact("B",false)),List.of(contact("B",true)),List.of());
        assertEquals("INVALID_RESPONSE",result(repo.refresh()).errorCode);assertEquals(1,count("contact"));assertTrue(blocked("Z"));
    }
    @Test public void roomUniqueFailureRollsBackRepositorySnapshot() {
        cache("Z",true);UserEntity old=db.userDao().findById("Z");old.setPhone("+84901111111");db.userDao().insert(old);
        Contact collision=contact("B",false);collision.phone="+84901111111";snapshot(List.of(collision),List.of(),List.of());
        assertEquals("CACHE_WRITE_FAILED",result(repo.refresh()).errorCode);assertTrue(blocked("Z"));
        assertNull(db.userDao().findById("B"));assertEquals(1,count("contact"));
    }
    @Test public void historicalAcceptCannotRecreateFriendOnFailedReload() {
        Request accepted=request("ACCEPTED");accepted.requester=user("B");accepted.addressee=user("A");
        reply(200,accepted);reply(503,Map.of());var r=result(repo.act("accept","r1",null));
        assertTrue(r.isSuccess());assertTrue(r.refreshRequired);assertEquals(0,count("contact"));
    }
    @Test public void blockUnfriendAndUnblockPreserveDecisionWithoutGuessingFriend() {
        cache("B",false);reply(204,null);reply(503,Map.of());assertTrue(result(repo.act("block","B",user("B"))).refreshRequired);
        assertTrue(blocked("B"));reply(204,null);reply(503,Map.of());assertTrue(result(repo.act("unfriend","B",null)).isSuccess());
        assertTrue(blocked("B"));reply(204,null);reply(503,Map.of());assertTrue(result(repo.act("unblock","B",null)).refreshRequired);
        assertEquals(0,count("contact"));
    }
    @Test public void sameActorReloginDropsQueuedRoomWrite() {
        Queue disk=new Queue();
        // Cùng API fixture nhưng giữ DISK để đổi phiên giữa HTTP và transaction.
        OkHttpClient client=clients.get(0);repo=repository(api(client,"http://127.0.0.1/"),disk);
        snapshot(List.of(contact("B",false)),List.of(),List.of());var old=repo.refresh();
        session.saveAuthenticated("repository-test-token","A",true);disk.drain();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertEquals(0,count("contact"));assertNull(old.getValue());
    }
    @Test public void observedRoomDataIsHiddenBeforeQueuedLogoutCleanup() {
        cache("B",false);var live=repo.getFriends("A");assertEquals(1,await(live,v->v.size()==1).size());
        // Giữ cleanup lại để chứng minh việc che dữ liệu không đợi SQLite xóa xong.
        Queue cleanup=new Queue();session.setOnSessionChanged(()->cleanup.execute(db::clearAllTables));session.clear();
        assertTrue(await(live,List::isEmpty).isEmpty());assertEquals(1,count("contact"));cleanup.drain();assertEquals(0,count("contact"));
    }
    @Test public void lostMutationAckIsUncertainAndDoesNotRetryOrChangeRoom() {
        cache("B",false);replies.add(new Reply(0,"",true));var r=result(repo.act("block","B",null));
        assertFalse(r.isSuccess());assertEquals("NETWORK",r.errorCode);assertTrue(r.refreshRequired);
        assertFalse(blocked("B"));assertEquals(1,calls.size());
    }
    @Test public void oldRoomRefreshCannotOverwriteConfirmedBlock() {
        Queue disk=new Queue();repo=repository(api(clients.get(0),"http://127.0.0.1/"),disk);
        snapshot(List.of(contact("B",false)),List.of(),List.of());var old=repo.refresh();
        reply(204,null);snapshot(List.of(),List.of(contact("B",true)),List.of());var action=repo.act("block","B",user("B"));
        disk.drain();assertEquals("STALE_RESULT",result(old).errorCode);assertTrue(result(action).isSuccess());
        assertEquals(1,count("contact"));assertTrue(blocked("B"));
    }
    @Test public void failedRoomPatchRollsBackButServerAckRemainsConfirmed() {
        cache("B",false);
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER reject_patch BEFORE INSERT ON contact BEGIN SELECT RAISE(ABORT,'fixture patch'); END");
        reply(204,null);reply(503,Map.of());var r=result(repo.act("block","B",user("B")));
        assertTrue(r.isSuccess());assertTrue(r.refreshRequired);assertFalse(blocked("B"));assertEquals(1,count("contact"));
        assertEquals("Cached B",db.userDao().findById("B").getDisplayName());
    }

    /** Hai mutation và sáu GET qua socket thật; server fixture không phải backend/HTTPS production. */
    @Test public void realTcpRetrofitSendCancelFlowReconcilesRoom() throws Exception {
        Request pending=request("PENDING");
        List<Reply> responses=List.of(new Reply(201,gson.toJson(pending),false),new Reply(200,"[]",false),
                new Reply(200,"[]",false),new Reply(200,gson.toJson(List.of(pending)),false),new Reply(204,"",false),
                new Reply(200,"[]",false),new Reply(200,"[]",false),new Reply(200,"[]",false));
        List<String> routes=new ArrayList<>();ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try {
                    for(Reply reply:responses) try(Socket socket=server.accept()) {
                        socket.setSoTimeout(5000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        routes.add(input.readLine());String line;int length=0;
                        while((line=input.readLine())!=null&&!line.isEmpty()) {
                            if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                        }
                        // Đọc hết body POST ASCII của fixture trước khi đóng socket, tránh reset kết nối vì dữ liệu chưa đọc.
                        char[] received=new char[length];int read=0;
                        while(read<length) { int n=input.read(received,read,length-read);if(n<0) throw new EOFException();read+=n; }
                        if(length>0) {
                            var json=com.google.gson.JsonParser.parseString(new String(received)).getAsJsonObject();
                            assertEquals(1,json.size());assertEquals("B",json.get("addresseeId").getAsString());
                        }
                        byte[] body=reply.json().getBytes(StandardCharsets.UTF_8);
                        var output=socket.getOutputStream();output.write(("HTTP/1.1 "+reply.code()+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        output.write(body);output.flush();
                    }
                } catch(IOException e){throw new UncheckedIOException(e);}
            });
            OkHttpClient tcp=new OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5,TimeUnit.SECONDS).build();clients.add(tcp);
            repo=repository(api(tcp,"http://127.0.0.1:"+server.getLocalPort()+"/"),Runnable::run);
            assertTrue(result(repo.act("send","B",null)).isSuccess());assertEquals(1,count("contact_request"));
            assertTrue(result(repo.act("cancel","r1",null)).isSuccess());assertEquals(0,count("contact_request"));
            served.get(10,TimeUnit.SECONDS);assertEquals(8,routes.size());assertTrue(routes.get(0).startsWith("POST /api/contact-requests "));
            assertTrue(routes.get(4).startsWith("DELETE /api/contact-requests/r1 "));
        } finally { serverThread.shutdownNow();assertTrue(serverThread.awaitTermination(5,TimeUnit.SECONDS)); }
    }
}
