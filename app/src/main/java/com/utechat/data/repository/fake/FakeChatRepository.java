package com.utechat.data.repository.fake;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.dao.ConversationListItem;
import com.utechat.data.local.dao.MessageDao;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.ConversationMemberEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.local.entity.UserEntity;
import com.utechat.data.repository.ChatRepository;
import com.utechat.data.repository.ChatResult;
import com.utechat.data.sync.WsConnectionState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Hội thoại giả phục vụ phát triển offline (USE_FAKE_DATA=true), tách hoàn toàn khỏi cache của API thật. */
public class FakeChatRepository implements ChatRepository {
    private final List<ConversationEntity> conversations = new ArrayList<>();
    private final Map<String, MutableLiveData<List<MessageEntity>>> messages = new HashMap<>();
    // Giữ luồng theo tài khoản để danh sách hội thoại cũng nhận cập nhật tin mới.
    private final Map<String, MutableLiveData<List<ConversationEntity>>> conversationStreams = new HashMap<>();
    private final Map<String, List<ConversationMemberEntity>> memberMap = new HashMap<>();
    private final Map<String, MutableLiveData<List<ConversationMemberEntity>>> memberStreams = new HashMap<>();
    private final Map<String, MutableLiveData<ConversationEntity>> conversationByIdStreams = new HashMap<>();
    private final MutableLiveData<List<ConversationListItem>> summariesStream = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Map<String, UserEntity>> allUsersStream = new MutableLiveData<>(Collections.emptyMap());
    private final MutableLiveData<WsConnectionState> wsState = new MutableLiveData<>(WsConnectionState.CONNECTED);
    private final Map<String, UserEntity> usersById = new LinkedHashMap<>();

    private long nextId = 1; // ID giả chỉ dùng trong bộ dữ liệu của instance này.

    public FakeChatRepository() {
        for (UserEntity u : DemoData.users()) {
            usersById.put(u.getId(), u);
        }
        allUsersStream.setValue(Collections.unmodifiableMap(new LinkedHashMap<>(usersById)));

        addConversation(DemoData.DIRECT, "DIRECT", "Bob Trần",
                java.util.Arrays.asList(DemoData.ALICE, DemoData.BOB));
        addConversation(DemoData.GROUP, "GROUP", "Nhóm Đồ Án Mobile UTEChat",
                java.util.Arrays.asList(DemoData.ALICE, DemoData.BOB, DemoData.CHARLIE));
        sendMessage(DemoData.DIRECT, DemoData.ALICE, "Chào Bob!");
        sendMessage(DemoData.GROUP, DemoData.ALICE, "Chào cả nhóm!");
    }

    private void addConversation(String id, String type, String title, List<String> memberIds) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTitle(title);
        conversations.add(conversation);
        messages.put(id, new MutableLiveData<>(new ArrayList<>()));
        conversationByIdStreams.put(id, new MutableLiveData<>(conversation));

        List<ConversationMemberEntity> members = new ArrayList<>();
        for (int i = 0; i < memberIds.size(); i++) {
            ConversationMemberEntity m = new ConversationMemberEntity();
            m.setConversationId(id);
            m.setUserId(memberIds.get(i));
            m.setRole("GROUP".equals(type) && i == 0 ? "ADMIN" : "MEMBER");
            m.setLeft(false);
            members.add(m);
        }
        memberMap.put(id, members);
        memberStreams.put(id, new MutableLiveData<>(new ArrayList<>(members)));
        refreshSummaries();
    }

    private boolean isMember(String conversationId, String userId) {
        List<ConversationMemberEntity> members = memberMap.get(conversationId);
        if (members == null) return false;
        for (ConversationMemberEntity m : members) {
            if (m.getUserId().equals(userId) && !m.getLeft()) return true;
        }
        return false;
    }

    @Override
    public LiveData<List<ConversationEntity>> getConversations(String userId) {
        return conversationStreams.computeIfAbsent(userId,
                id -> new MutableLiveData<>(visibleConversations(id)));
    }

    private List<ConversationEntity> visibleConversations(String userId) {
        List<ConversationEntity> visible = new ArrayList<>();
        for (ConversationEntity conversation : conversations) {
            if (isMember(conversation.getId(), userId)) visible.add(conversation);
        }
        return visible;
    }

    @Override
    public LiveData<List<MessageEntity>> getMessages(String conversationId) {
        MutableLiveData<List<MessageEntity>> result = messages.get(conversationId);
        return result == null ? new MutableLiveData<>(Collections.emptyList()) : result;
    }

    @Override
    public LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text) {
        // Không chấp nhận hội thoại/người gửi ngoài fixture hoặc nội dung rỗng.
        if (!isMember(conversationId, senderId) || text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Tin nhắn mẫu không hợp lệ");
        }
        MessageEntity message = new MessageEntity();
        message.setLocalId(nextId);
        message.setServerId(nextId++);
        message.setClientId(UUID.randomUUID().toString());
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setType("TEXT");
        message.setText(text);
        message.setNormalizedText(MessageText.normalize(text));
        message.setCreatedAt(System.currentTimeMillis());
        message.setStatus("SENT");
        // Phát một danh sách mới để observer nhận được thay đổi, không sửa list đang hiển thị.
        MutableLiveData<List<MessageEntity>> stream = messages.get(conversationId);
        List<MessageEntity> copy = new ArrayList<>(stream.getValue() != null ? stream.getValue() : Collections.emptyList());
        copy.add(message);
        stream.setValue(copy);
        for (ConversationEntity conversation : conversations) {
            if (conversation.getId().equals(conversationId)) {
                conversation.setLastMessageId(message.getServerId());
                conversation.setLastMessageAt(message.getCreatedAt());
                MutableLiveData<ConversationEntity> single = conversationByIdStreams.get(conversationId);
                if (single != null) single.setValue(conversation);
            }
        }
        // Phát lại list cho từng người đang quan sát, giữ đúng phạm vi thành viên.
        for (Map.Entry<String, MutableLiveData<List<ConversationEntity>>> entry : conversationStreams.entrySet()) {
            entry.getValue().setValue(visibleConversations(entry.getKey()));
        }
        refreshSummaries();
        return new MutableLiveData<>(message);
    }

    // ======================== PHASE 5 IN-MEMORY METHODS ========================

    @Override
    public LiveData<List<ConversationListItem>> observeConversationSummaries() {
        return summariesStream;
    }

    @Override
    public LiveData<ConversationEntity> observeConversation(String conversationId) {
        return conversationByIdStreams.computeIfAbsent(conversationId, k -> new MutableLiveData<>(null));
    }

    @Override
    public LiveData<List<ConversationMemberEntity>> observeMembers(String conversationId) {
        return memberStreams.computeIfAbsent(conversationId, k -> new MutableLiveData<>(Collections.emptyList()));
    }

    @Override
    public LiveData<List<MessageEntity>> observeVisibleMessages(String conversationId, int limit) {
        MutableLiveData<List<MessageEntity>> stream = messages.get(conversationId);
        if (stream == null) return new MutableLiveData<>(Collections.emptyList());
        return androidx.lifecycle.Transformations.map(stream, list -> {
            if (list == null || list.size() <= limit) return list == null ? Collections.emptyList() : list;
            return new ArrayList<>(list.subList(list.size() - limit, list.size()));
        });
    }

    @Override
    public LiveData<UserEntity> observeUser(String userId) {
        return new MutableLiveData<>(usersById.get(userId));
    }

    @Override
    public LiveData<Map<String, UserEntity>> observeAllUsersMap() {
        return allUsersStream;
    }

    @Override
    public LiveData<WsConnectionState> observeWsState() {
        return wsState;
    }

    @Override
    public LiveData<Set<String>> observeTypingUsers(String conversationId) {
        return new MutableLiveData<>(Collections.emptySet());
    }

    @Override
    public LiveData<ChatResult<String>> openOrCreateDirect(String peerUserId) {
        for (ConversationEntity c : conversations) {
            if ("DIRECT".equals(c.getType()) && isMember(c.getId(), peerUserId)) {
                return new MutableLiveData<>(ChatResult.ok(c.getId()));
            }
        }
        UserEntity peer = usersById.get(peerUserId);
        String newId = UUID.randomUUID().toString();
        addConversation(newId, "DIRECT", peer != null ? peer.getDisplayName() : "Chat",
                java.util.Arrays.asList(DemoData.ALICE, peerUserId));
        return new MutableLiveData<>(ChatResult.ok(newId));
    }

    @Override
    public LiveData<ChatResult<String>> createGroup(String title, List<String> memberIds) {
        List<String> all = new ArrayList<>();
        all.add(DemoData.ALICE);
        if (memberIds != null) {
            for (String id : memberIds) {
                if (!all.contains(id)) all.add(id);
            }
        }
        String newId = UUID.randomUUID().toString();
        addConversation(newId, "GROUP", title, all);
        return new MutableLiveData<>(ChatResult.ok(newId));
    }

    @Override
    public LiveData<ChatResult<Boolean>> renameGroup(String conversationId, String title) {
        for (ConversationEntity c : conversations) {
            if (c.getId().equals(conversationId)) {
                c.setTitle(title);
                MutableLiveData<ConversationEntity> single = conversationByIdStreams.get(conversationId);
                if (single != null) single.setValue(c);
                refreshSummaries();
                return new MutableLiveData<>(ChatResult.ok(true));
            }
        }
        return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
    }

    @Override
    public LiveData<ChatResult<Boolean>> setMuted(String conversationId, boolean muted) {
        for (ConversationEntity c : conversations) {
            if (c.getId().equals(conversationId)) {
                c.setMuted(muted);
                refreshSummaries();
                return new MutableLiveData<>(ChatResult.ok(true));
            }
        }
        return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
    }

    @Override
    public LiveData<ChatResult<Boolean>> addMembers(String conversationId, List<String> userIds) {
        List<ConversationMemberEntity> list = memberMap.get(conversationId);
        if (list == null) return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
        if (userIds != null) {
            for (String uid : userIds) {
                if (!isMember(conversationId, uid)) {
                    ConversationMemberEntity m = new ConversationMemberEntity();
                    m.setConversationId(conversationId);
                    m.setUserId(uid);
                    m.setRole("MEMBER");
                    list.add(m);
                }
            }
        }
        MutableLiveData<List<ConversationMemberEntity>> s = memberStreams.get(conversationId);
        if (s != null) s.setValue(new ArrayList<>(list));
        return new MutableLiveData<>(ChatResult.ok(true));
    }

    @Override
    public LiveData<ChatResult<Boolean>> removeOrLeaveMember(
            String conversationId, String targetUserId, @Nullable String successorUserId) {
        List<ConversationMemberEntity> list = memberMap.get(conversationId);
        if (list == null) return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
        for (ConversationMemberEntity m : list) {
            if (m.getUserId().equals(targetUserId)) m.setLeft(true);
            if (successorUserId != null && m.getUserId().equals(successorUserId)) m.setRole("ADMIN");
        }
        MutableLiveData<List<ConversationMemberEntity>> s = memberStreams.get(conversationId);
        if (s != null) s.setValue(new ArrayList<>(list));
        return new MutableLiveData<>(ChatResult.ok(true));
    }

    @Override
    public LiveData<ChatResult<Boolean>> updateMemberRole(String conversationId, String targetUserId, String role) {
        List<ConversationMemberEntity> list = memberMap.get(conversationId);
        if (list == null) return new MutableLiveData<>(ChatResult.error("NOT_FOUND"));
        for (ConversationMemberEntity m : list) {
            if (m.getUserId().equals(targetUserId)) m.setRole(role);
        }
        MutableLiveData<List<ConversationMemberEntity>> s = memberStreams.get(conversationId);
        if (s != null) s.setValue(new ArrayList<>(list));
        return new MutableLiveData<>(ChatResult.ok(true));
    }

    @Override
    public LiveData<ChatResult<Integer>> loadOlderMessages(String conversationId, @Nullable Long beforeServerId, int limit) {
        return new MutableLiveData<>(ChatResult.ok(0));
    }

    @Override
    public void enqueueTextMessage(String conversationId, String text) {
        if (text != null && !text.trim().isEmpty() && isMember(conversationId, DemoData.ALICE)) {
            sendMessage(conversationId, DemoData.ALICE, text.trim());
        }
    }

    @Override
    public void retryFailedMessage(String clientId) { /* Dữ liệu giả gửi tức thì thành công. */ }

    @Override
    public void deleteFailedMessage(String clientId) { /* Không có hàng đợi lỗi cục bộ trong chế độ giả. */ }

    @Override
    public void markConversationRead(String conversationId, long upToServerId) {
        for (ConversationEntity c : conversations) {
            if (c.getId().equals(conversationId)) {
                c.setUnreadCount(0);
                refreshSummaries();
            }
        }
    }

    @Override public void sendTypingStart(String conversationId) { }
    @Override public void sendTypingStop(String conversationId) { }

    @Override
    public LiveData<List<MessageEntity>> searchInConversation(String conversationId, String query) {
        if (MessageText.safeMatchQuery(query) == null) {
            return new MutableLiveData<>(Collections.emptyList());
        }
        String needle = MessageText.normalize(query);
        MutableLiveData<List<MessageEntity>> stream = messages.get(conversationId);
        List<MessageEntity> hits = new ArrayList<>();
        if (stream != null && stream.getValue() != null && needle != null) {
            for (MessageEntity m : stream.getValue()) {
                if (m.getNormalizedText() != null && m.getNormalizedText().contains(needle)) {
                    hits.add(m);
                }
            }
        }
        Collections.reverse(hits);
        return new MutableLiveData<>(hits);
    }

    @Override
    public LiveData<List<MessageDao.MessageSearchHit>> searchGlobalMessages(String query, int limit) {
        if (MessageText.safeMatchQuery(query) == null) {
            return new MutableLiveData<>(Collections.emptyList());
        }
        String needle = MessageText.normalize(query);
        List<MessageDao.MessageSearchHit> hits = new ArrayList<>();
        if (needle != null) {
            for (ConversationEntity conv : conversations) {
                MutableLiveData<List<MessageEntity>> stream = messages.get(conv.getId());
                if (stream == null || stream.getValue() == null) continue;
                for (MessageEntity m : stream.getValue()) {
                    if (m.getNormalizedText() != null && m.getNormalizedText().contains(needle)) {
                        MessageDao.MessageSearchHit hit = new MessageDao.MessageSearchHit();
                        hit.message = m;
                        hit.conversationTitle = conv.getTitle();
                        hit.conversationType = conv.getType();
                        hits.add(hit);
                    }
                }
            }
        }
        if (hits.size() > limit) hits = hits.subList(0, limit);
        return new MutableLiveData<>(hits);
    }

    @Override
    public LiveData<Integer> computeWindowLimitForMessage(String conversationId, long targetLocalId, int minLimit) {
        return new MutableLiveData<>(minLimit);
    }

    @Override public void triggerSync() { }
    @Override public void connectRealtime() { wsState.setValue(WsConnectionState.CONNECTED); }
    @Override public void disconnectRealtime() { wsState.setValue(WsConnectionState.DISCONNECTED); }
    @Override @Nullable public String currentUserId() { return DemoData.ALICE; }

    private void refreshSummaries() {
        List<ConversationListItem> items = new ArrayList<>();
        for (ConversationEntity conv : conversations) {
            ConversationListItem item = new ConversationListItem();
            item.conversation = conv;
            if ("DIRECT".equals(conv.getType())) {
                List<ConversationMemberEntity> members = memberMap.get(conv.getId());
                if (members != null) {
                    for (ConversationMemberEntity m : members) {
                        if (!DemoData.ALICE.equals(m.getUserId())) {
                            item.peerUserId = m.getUserId();
                            UserEntity peer = usersById.get(m.getUserId());
                            if (peer != null) {
                                item.peerDisplayName = peer.getDisplayName();
                                item.peerAvatarUrl = peer.getAvatarUrl();
                            }
                            break;
                        }
                    }
                }
            }
            MutableLiveData<List<MessageEntity>> stream = messages.get(conv.getId());
            if (stream != null && stream.getValue() != null && !stream.getValue().isEmpty()) {
                MessageEntity last = stream.getValue().get(stream.getValue().size() - 1);
                item.lastMessageType = last.getType();
                item.lastMessageText = last.getText();
                item.lastMessageAttachmentJson = last.getAttachmentJson();
                item.lastMessageSenderId = last.getSenderId();
                item.lastMessageStatus = last.getStatus();
                item.lastMessageServerId = last.getServerId();
            }
            items.add(item);
        }
        items.sort((a, b) -> Long.compare(b.conversation.getLastMessageAt(), a.conversation.getLastMessageAt()));
        summariesStream.setValue(items);
    }
}
