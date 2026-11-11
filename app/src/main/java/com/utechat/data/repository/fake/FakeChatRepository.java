package com.utechat.data.repository.fake;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.utechat.data.local.MessageText;
import com.utechat.data.local.entity.ConversationEntity;
import com.utechat.data.local.entity.MessageEntity;
import com.utechat.data.repository.ChatRepository;
import java.util.*;

/** Hội thoại giả phục vụ phát triển offline, tách hoàn toàn khỏi cache của API thật. */
public class FakeChatRepository implements ChatRepository {
    private final List<ConversationEntity> conversations = new ArrayList<>();
    private final Map<String, MutableLiveData<List<MessageEntity>>> messages = new HashMap<>();
    // Giữ luồng theo tài khoản để danh sách hội thoại cũng nhận cập nhật tin mới.
    private final Map<String, MutableLiveData<List<ConversationEntity>>> conversationStreams = new HashMap<>();
    private long nextId = 1; // ID giả chỉ dùng trong bộ dữ liệu của instance này.

    public FakeChatRepository() {
        addConversation(DemoData.DIRECT, "DIRECT", "Bob Trần");
        addConversation(DemoData.GROUP, "GROUP", "Nhóm Đồ Án Mobile UTEChat");
        sendMessage(DemoData.DIRECT, DemoData.ALICE, "Chào Bob!");
        sendMessage(DemoData.GROUP, DemoData.ALICE, "Chào cả nhóm!");
    }

    private void addConversation(String id, String type, String title) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTitle(title);
        conversations.add(conversation);
        messages.put(id, new MutableLiveData<>(new ArrayList<>()));
    }

    private boolean isMember(String conversationId, String userId) {
        return (DemoData.DIRECT.equals(conversationId) || DemoData.GROUP.equals(conversationId))
                && (DemoData.ALICE.equals(userId) || DemoData.BOB.equals(userId)
                || (DemoData.GROUP.equals(conversationId) && DemoData.CHARLIE.equals(userId)));
    }

    @Override public LiveData<List<ConversationEntity>> getConversations(String userId) {
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

    @Override public LiveData<List<MessageEntity>> getMessages(String conversationId) {
        MutableLiveData<List<MessageEntity>> result = messages.get(conversationId);
        return result == null ? new MutableLiveData<>(Collections.emptyList()) : result;
    }

    @Override public LiveData<MessageEntity> sendMessage(String conversationId, String senderId, String text) {
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
        List<MessageEntity> copy = new ArrayList<>(stream.getValue());
        copy.add(message);
        stream.setValue(copy);
        for (ConversationEntity conversation : conversations) {
            if (conversation.getId().equals(conversationId)) {
                conversation.setLastMessageId(message.getServerId());
                conversation.setLastMessageAt(message.getCreatedAt());
            }
        }
        // Phát lại list cho từng người đang quan sát, giữ đúng phạm vi thành viên.
        for (Map.Entry<String, MutableLiveData<List<ConversationEntity>>> entry : conversationStreams.entrySet()) {
            entry.getValue().setValue(visibleConversations(entry.getKey()));
        }
        return new MutableLiveData<>(message);
    }
}
