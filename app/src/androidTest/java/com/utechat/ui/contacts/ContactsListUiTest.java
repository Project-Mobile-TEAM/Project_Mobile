package com.utechat.ui.contacts;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import android.content.res.Configuration;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.*;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.utechat.R;
import com.utechat.data.local.dao.ContactRequestWithUser;
import com.utechat.data.local.entity.ContactRequestEntity;
import com.utechat.data.remote.model.ContactDtos.User;
import com.utechat.data.repository.*;
import com.utechat.data.repository.fake.*;
import com.utechat.di.Injection;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Mục 16: View/XML, graph và DialogFragment thật; dữ liệu fixture không phải backend/Room. */
@RunWith(AndroidJUnit4.class)
public class ContactsListUiTest {
    private ActivityScenario<ContactsUiTestActivity> scenario;
    private Fixture repo;
    private FakeAuthRepository auth;
    private Field contactsField;
    private Object previous;
    private int previousNight;
    private LocaleListCompat previousLocales;
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    // AppCompat 1.6.1 tìm LocaleManager qua Activity sống; trước launch phải dùng Context trên API 33+.
    private LocaleListCompat applicationLocales() {
        if(android.os.Build.VERSION.SDK_INT>=33) return LocaleListCompat.wrap(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSystemService(android.app.LocaleManager.class).getApplicationLocales());
        return AppCompatDelegate.getApplicationLocales();
    }
    private void applicationLocales(LocaleListCompat value) {
        if(android.os.Build.VERSION.SDK_INT>=33) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(android.app.LocaleManager.class).setApplicationLocales(android.os.LocaleList.forLanguageTags(value.toLanguageTags()));
        else AppCompatDelegate.setApplicationLocales(value);
    }
    @Before public void setup() throws Exception {
        contactsField=Injection.class.getDeclaredField("contacts");contactsField.setAccessible(true);
        previous=contactsField.get(null);previousNight=AppCompatDelegate.getDefaultNightMode();previousLocales=applicationLocales();
        main(()->{
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            applicationLocales(LocaleListCompat.forLanguageTags("en"));
            auth=new FakeAuthRepository();auth.login("+84901111111","");repo=new Fixture(auth);
        });
        contactsField.set(null,repo);
    }
    @After public void cleanup() throws Exception {
        if(scenario!=null) scenario.close();
        contactsField.set(null,previous);
        // API không chấp nhận MODE_NIGHT_UNSPECIFIED; hành vi mặc định của giá trị này là FOLLOW_SYSTEM.
        main(()->{applicationLocales(previousLocales);AppCompatDelegate.setDefaultNightMode(previousNight==AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
                ?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:previousNight);});
    }
    private void launch() { scenario=ActivityScenario.launch(ContactsUiTestActivity.class);rows(2); }
    private NavHostFragment host(ContactsUiTestActivity activity) {
        return (NavHostFragment)activity.getSupportFragmentManager().findFragmentByTag("contacts.test.host");
    }
    /** Chờ AsyncListDiffer theo điều kiện thực, không xem luồng Espresso rảnh là diff đã xong. */
    private void rows(int expected) {
        rows(R.id.list,expected);
    }
    /** Các màn con dùng RecyclerView riêng; vẫn chờ diff thật trước khi bấm thao tác. */
    private void rows(int listId,int expected) {
        AtomicInteger actual=new AtomicInteger(-1);long until=android.os.SystemClock.uptimeMillis()+5000;
        do {
            scenario.onActivity(a->{RecyclerView list=a.findViewById(listId);actual.set(list.getAdapter().getItemCount());});
            if(actual.get()==expected) return;
            android.os.SystemClock.sleep(20);
        } while(android.os.SystemClock.uptimeMillis()<until);
        assertEquals("Số hàng sau diff",expected,actual.get());
    }
    private void filterBob() { onView(withId(R.id.filter)).perform(replaceText("bOB"),closeSoftKeyboard());rows(1); }
    private void menu(int label) {
        filterBob();onView(withId(R.id.actions)).perform(click());onView(withText(label)).perform(click());
    }

    // Danh sách, bộ lọc và badge dùng các View thật trong Fragment.
    @Test public void listsFriendsAndFiltersNameWithoutChangingCache() {
        try {
            launch();onView(withText("Bob Trần")).check(matches(isDisplayed()));filterBob();
            onView(withText("Bob Trần")).check(matches(isDisplayed()));
            onView(withId(R.id.filter)).perform(replaceText("not a friend"),closeSoftKeyboard());rows(0);
            onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_no_match)));
            onView(withId(R.id.filter)).perform(replaceText(""),closeSoftKeyboard());rows(2);
        } catch(RuntimeException|Error failure) {
            // Chụp đúng lúc lỗi, trước @After đóng Activity; luôn ném lại lỗi gốc, không làm test xanh.
            ContactsUiDiagnostics.capture(scenario,failure);throw failure;
        }
    }
    @Test public void pendingIncomingBadgeExcludesOutgoingAndHistory() {
        launch();scenario.onActivity(a->assertEquals(a.getString(R.string.contacts_count,1),((TextView)a.findViewById(R.id.requests)).getText().toString()));
        main(()->repo.seedRequests(List.of()));
        onView(withId(R.id.requests)).check(matches(withText(R.string.contacts_requests)));
    }
    @Test public void filterAndViewModelSurviveRecreateWithoutDuplicateLoad() {
        launch();filterBob();scenario.recreate();rows(1);
        onView(withId(R.id.filter)).check(matches(withText("bOB")));assertEquals(1,repo.refreshCalls);
    }
    @Test public void pullRefreshDisablesActionsUntilCompletion() {
        launch();main(()->repo.pending=new MutableLiveData<>());
        onView(withId(R.id.swipe)).perform(swipeDown());
        onView(withId(R.id.refresh)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        scenario.onActivity(a->assertTrue(((SwipeRefreshLayout)a.findViewById(R.id.swipe)).isRefreshing()));
        main(()->repo.pending.setValue(ContactsResult.ok(true)));
        onView(withId(R.id.refresh)).check(matches(isEnabled()));assertEquals(2,repo.refreshCalls);
    }
    @Test public void cancelUnfriendNeverCallsRepository() {
        launch();menu(R.string.contacts_unfriend);onView(withText(R.string.contacts_no)).perform(click());
        assertEquals(0,repo.mutations);rows(1);
    }
    @Test public void confirmUnfriendRemovesOnlySelectedFriend() {
        launch();menu(R.string.contacts_unfriend);onView(withId(android.R.id.button1)).perform(click());rows(0);
        assertEquals(1,repo.mutations);assertEquals("unfriend",repo.lastAction);assertEquals(DemoData.BOB,repo.lastTarget);
        onView(withId(R.id.filter)).perform(replaceText(""),closeSoftKeyboard());rows(1);
        onView(withText("Charlie Lê")).check(matches(isDisplayed()));
        // Xóa nốt fixture còn lại để phân biệt danh sách rỗng với bộ lọc không khớp.
        onView(withId(R.id.actions)).perform(click());onView(withText(R.string.contacts_unfriend)).perform(click());
        onView(withId(android.R.id.button1)).perform(click());rows(0);
        onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_empty)));assertEquals(2,repo.mutations);
    }
    @Test public void blockConfirmationSurvivesRecreateAndCallsOnce() {
        launch();menu(R.string.contacts_block);scenario.recreate();
        // Sau recreate, chọn cửa sổ hộp thoại thay vì Activity phía dưới chưa có focus.
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());rows(0);
        assertEquals(1,repo.mutations);assertEquals("block",repo.lastAction);assertEquals(DemoData.BOB,repo.lastTarget);
    }
    @Test public void navigationIdsAndBackKeepFilterAndSingleViewModel() {
        launch();filterBob();int[] buttons={R.id.find,R.id.requests,R.id.blocked};
        int[] destinations={R.id.searchUserFragment,R.id.friendRequestsFragment,R.id.blockedUsersFragment};
        for(int i=0;i<buttons.length;i++) {
            onView(withId(buttons[i])).perform(click());final int target=destinations[i];
            scenario.onActivity(a->{assertEquals(target,host(a).getNavController().getCurrentDestination().getId());assertTrue(host(a).getNavController().navigateUp());});
            rows(1);onView(withId(R.id.filter)).check(matches(withText("bOB")));
        }
        assertEquals(1,repo.refreshCalls);
    }
    @Test public void offlineRetainsRowsAndRetryOnlyRefreshes() throws InterruptedException {
        repo.refreshError="NETWORK";launch();
        // Snackbar đầu phiên có thể hết hạn trên máy chậm; giữ riêng một bar khi kiểm parent, không sleep đoán thời gian.
        AtomicReference<com.google.android.material.snackbar.Snackbar> bar=new AtomicReference<>();
        CountDownLatch shown=new CountDownLatch(1);
        scenario.onActivity(a->{
            var fragment=host(a).getChildFragmentManager().getPrimaryNavigationFragment();
            bar.set(ContactsUi.notice(fragment,fragment.requireView(),ContactsResult.error("NETWORK"),()->{})
                    .setDuration(com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                    .addCallback(new com.google.android.material.snackbar.Snackbar.Callback() {
                        @Override public void onShown(com.google.android.material.snackbar.Snackbar value) { shown.countDown(); }
                    }));
        });
        // Main looper rảnh không đồng nghĩa animation/queue Snackbar xong; chờ đúng callback, không sleep.
        assertTrue("Snackbar phải báo onShown trước khi kiểm parent",shown.await(5,TimeUnit.SECONDS));
        scenario.onActivity(a->{
            android.view.ViewParent parent=bar.get().getView().getParent();
            assertNotNull("Snackbar đã được gắn vào cửa sổ",parent);
            while(parent instanceof android.view.View && parent!=a.findViewById(android.R.id.content)) {
                assertFalse("Không gắn Snackbar vào FragmentContainerView",parent instanceof androidx.fragment.app.FragmentContainerView);
                parent=parent.getParent();
            }
            assertSame(a.findViewById(android.R.id.content),parent);
            bar.get().dismiss();
        });
        onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_network)));
        repo.refreshError=null;onView(withId(R.id.refresh)).perform(click());rows(2);
        assertEquals(2,repo.refreshCalls);assertEquals(0,repo.mutations);
    }
    @Test public void uncertainMutationRequiresRefreshAndNeverRepeatsMutation() {
        launch();ContactsResult<Boolean> uncertain=ContactsResult.error("NETWORK");uncertain.refreshRequired=true;repo.mutationResult=uncertain;
        menu(R.string.contacts_block);onView(withId(android.R.id.button1)).perform(click());
        onView(withId(R.id.actions)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_uncertain)));
        onView(withId(R.id.refresh)).perform(click());assertEquals(1,repo.mutations);assertEquals(2,repo.refreshCalls);
        onView(withId(R.id.actions)).check(matches(isEnabled()));
    }
    @Test public void logoutClearsRowsAndDisablesPersonalActionsImmediately() {
        launch();main(auth::logout);rows(0);
        onView(withId(R.id.filter)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        onView(withId(R.id.find)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_session_changed)));
    }
    @Test public void vietnameseNightResourcesAndAccessibleActionsUseCurrentTheme() {
        main(()->{applicationLocales(LocaleListCompat.forLanguageTags("vi"));AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);});
        launch();filterBob();
        scenario.onActivity(a->{
            assertEquals(Configuration.UI_MODE_NIGHT_YES,a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);
            assertEquals("vi",a.getResources().getConfiguration().getLocales().get(0).getLanguage());
            assertEquals(a.getString(R.string.title_contacts),((com.google.android.material.appbar.MaterialToolbar)a.findViewById(R.id.toolbar)).getTitle().toString());
            assertEquals(a.getString(R.string.contacts_actions_for,"Bob Trần"),a.findViewById(R.id.actions).getContentDescription().toString());
        });
    }

    /** Kiểm tra màn mới với Repository giả: lời mời phải gửi đúng ID và đúng chiều. */
    @Test public void requestScreenRoutesAcceptRejectAndCancelToRequestIds() {
        repo.mutationResult=ContactsResult.ok(true);launch();
        onView(withId(R.id.requests)).perform(click());
        rows(R.id.incoming_list,1);rows(R.id.outgoing_list,1);
        onView(org.hamcrest.Matchers.allOf(withId(R.id.chip_filled),isDescendantOfA(withId(R.id.incoming_list)))).perform(click());
        assertEquals("accept",repo.lastAction);assertEquals("incoming-PENDING",repo.lastTarget);
        onView(org.hamcrest.Matchers.allOf(withId(R.id.chip_tonal),isDescendantOfA(withId(R.id.incoming_list)))).perform(click());
        assertEquals("reject",repo.lastAction);assertEquals("incoming-PENDING",repo.lastTarget);
        onView(org.hamcrest.Matchers.allOf(withId(R.id.chip_outlined),isDescendantOfA(withId(R.id.outgoing_list)))).perform(click());
        assertEquals("cancel",repo.lastAction);assertEquals("outgoing-PENDING",repo.lastTarget);assertEquals(3,repo.mutations);
    }

    /** Nhập số, tìm người lạ và xoay màn hình phải giữ đúng người nhận khi gửi lời mời. */
    @Test public void searchSurvivesRecreationAndSendsToSelectedUser() {
        launch();openDavidSearch();scenario.recreate();rows(R.id.result,1);
        onView(withId(R.id.chip_filled)).perform(click());
        assertEquals("send",repo.lastAction);assertEquals(DemoData.DAVID,repo.lastTarget);assertEquals(1,repo.mutations);
    }

    /** Người lạ cũng cần lối chặn; không chỉ chặn được người đã nằm trong danh sách bạn. */
    @Test public void searchCanBlockStrangerAfterConfirmation() {
        launch();openDavidSearch();
        onView(withId(R.id.actions)).perform(click());onView(withText(R.string.contacts_block)).perform(click());
        assertEquals(0,repo.mutations);
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        assertEquals("block",repo.lastAction);assertEquals(DemoData.DAVID,repo.lastTarget);assertEquals(1,repo.mutations);
    }

    /** ACK chưa chắc chắn chỉ được đối soát cache; nút thử lại không gửi lặp thao tác ghi. */
    @Test public void searchRetryReconcilesUncertainMutationBeforeEnablingActions() {
        launch();openDavidSearch();
        ContactsResult<Boolean> uncertain=ContactsResult.error("NETWORK");uncertain.refreshRequired=true;repo.mutationResult=uncertain;
        onView(withId(R.id.chip_filled)).perform(click());
        onView(withId(com.google.android.material.R.id.snackbar_action)).perform(click());
        assertEquals(2,repo.refreshCalls);assertEquals(1,repo.mutations);
        // Tìm lại sau đối soát phải cho phép thao tác, không kẹt needsRefresh mãi mãi.
        onView(withId(R.id.find)).perform(click());rows(R.id.result,1);
        onView(withId(R.id.chip_filled)).check(matches(isEnabled()));
    }

    /** Bỏ chặn chỉ chạy sau xác nhận và danh sách cập nhật từ nguồn quan hệ. */
    @Test public void blockedScreenCancelsThenConfirmsUnblock() {
        launch();onView(withId(R.id.blocked)).perform(click());rows(1);
        onView(withId(R.id.chip_outlined)).perform(click());
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());assertEquals(0,repo.mutations);
        onView(withId(R.id.chip_outlined)).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());rows(0);
        assertEquals("unblock",repo.lastAction);assertEquals(DemoData.EVE,repo.lastTarget);assertEquals(1,repo.mutations);
        onView(withId(R.id.empty)).check(matches(withText(R.string.contacts_blocked_empty)));
    }

    private void openDavidSearch() {
        onView(withId(R.id.find)).perform(click());
        onView(withId(R.id.phone)).perform(replaceText("904444444"),closeSoftKeyboard());
        onView(withId(R.id.find)).perform(click());rows(R.id.result,1);
    }

    /** Fixture UI có cache demo; trạng thái chờ/lỗi tách khỏi bộ test Room thật hồi quy. */
    private static final class Fixture extends FakeContactsRepository {
        int refreshCalls,mutations;
        String lastAction,lastTarget,refreshError;
        MutableLiveData<ContactsResult<Boolean>> pending;
        ContactsResult<Boolean> mutationResult;
        final MutableLiveData<List<ContactRequestWithUser>> requestRows=new MutableLiveData<>();
        Fixture(CurrentUserIdProvider provider) { super(provider);seedRequests(List.of(request("PENDING",false),request("PENDING",true),request("ACCEPTED",false))); }
        void seedRequests(List<ContactRequestWithUser> value) { requestRows.setValue(value); }
        private static ContactRequestWithUser request(String status,boolean outgoing) {
            ContactRequestWithUser row=new ContactRequestWithUser();row.request=new ContactRequestEntity();row.request.setStatus(status);
            // ID/tên tối thiểu để màn lời mời kiểm được thao tác theo chiều, không phụ thuộc dữ liệu server.
            row.request.setId((outgoing?"outgoing-":"incoming-")+status);row.requesterName="David";row.addresseeName="David";
            row.request.setRequesterId(outgoing?DemoData.ALICE:DemoData.DAVID);row.request.setAddresseeId(outgoing?DemoData.DAVID:DemoData.ALICE);return row;
        }
        @Override public LiveData<List<ContactRequestWithUser>> getRequests() { return requestRows; }
        @Override public LiveData<Integer> incomingCount(String owner) {
            return Transformations.map(requestRows,rows->(int)rows.stream().filter(r->"PENDING".equals(r.request.getStatus())&&owner.equals(r.request.getAddresseeId())).count());
        }
        @Override public LiveData<ContactsResult<Boolean>> refresh() {
            refreshCalls++;return pending!=null?pending:refreshError!=null?new MutableLiveData<>(ContactsResult.error(refreshError)):super.refresh();
        }
        @Override public LiveData<ContactsResult<Boolean>> act(String action,String id,User user) {
            mutations++;lastAction=action;lastTarget=id;
            return mutationResult==null?super.act(action,id,user):new MutableLiveData<>(mutationResult);
        }
    }
}
