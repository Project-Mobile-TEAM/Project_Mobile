package com.utechat.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.utechat.backend.dto.MessagingDtos.AddMembersRequest;
import com.utechat.backend.dto.MessagingDtos.ConversationDto;
import com.utechat.backend.dto.MessagingDtos.CreateGroupRequest;
import com.utechat.backend.dto.MessagingDtos.MemberDto;
import com.utechat.backend.dto.MessagingDtos.MessageDto;
import com.utechat.backend.dto.MessagingDtos.UpdateConversationRequest;
import com.utechat.backend.dto.MessagingDtos.UpdateRoleRequest;
import com.utechat.backend.exception.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ quản lý hội thoại 1-1 và nhóm chat (Plan 05-01 & 05-02):
 * direct_key chống trùng, tạo/đổi tên/muted nhóm, giới hạn 50 thành viên, bạn bè ACCEPTED,
 * cô lập lịch sử chặt visible_from_message_id (>), bàn giao ADMIN nguyên tử và tin SYSTEM idempotent.
 */
@Service
public class ConversationService {
    public static final int MAX_GROUP_MEMBERS = 50;

    private final JdbcTemplate jdbc;
    private final ContactPolicyService policy;
    private final SyncEventWriter syncEvents;
    private final ObjectMapper json;

    public ConversationService(
            JdbcTemplate jdbc,
            ContactPolicyService policy,
            SyncEventWriter syncEvents,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.syncEvents = syncEvents;
        this.json = json;
    }

    /**
     * Mở lại hội thoại 1-1 sẵn có hoặc tạo mới theo direct_key duy nhất (minUuid:maxUuid).
     * Luôn khóa cặp bằng lockPair trước và kiểm tra 403 BLOCKED nghiêm ngặt trước 403 CONTACT_REQUIRED.
     */
    @Transactional
    public ConversationDto openOrCreateDirect(UUID actorId, String rawPeerUserId) {
        UUID peerId = parseRequiredUuid(rawPeerUserId, "Invalid userId");
        if (actorId.equals(peerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF", "Cannot start direct chat with yourself");
        }

        // Khóa cặp không phân biệt chiều trước khi đọc/tạo để chống race condition tạo trùng direct_key.
        policy.lockPair(actorId, peerId);

        if (!isActiveUser(actorId) || !isActiveUser(peerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
        }

        // Chính sách D-01: luôn kiểm tra BLOCKED trước CONTACT_REQUIRED ở cả mở chat lẫn gửi tin.
        enforceDirectPolicy(actorId, peerId);

        String directKey = buildDirectKey(actorId, peerId);
        List<UUID> existing = jdbc.query(
                "SELECT id FROM conversations WHERE direct_key = ?",
                (rs, i) -> rs.getObject("id", UUID.class),
                directKey);

        UUID conversationId;
        boolean newlyCreated = false;
        if (!existing.isEmpty()) {
            conversationId = existing.get(0);
            ensureDirectMembers(conversationId, actorId, peerId);
        } else {
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO conversations (type, direct_key, created_by) VALUES ('DIRECT', ?, ?) "
                            + "ON CONFLICT (direct_key) DO NOTHING RETURNING id",
                    (rs, i) -> rs.getObject("id", UUID.class),
                    directKey, actorId);
            if (!inserted.isEmpty()) {
                conversationId = inserted.get(0);
                newlyCreated = true;
            } else {
                conversationId = jdbc.queryForObject(
                        "SELECT id FROM conversations WHERE direct_key = ?",
                        (rs, i) -> rs.getObject("id", UUID.class),
                        directKey);
            }
            ensureDirectMembers(conversationId, actorId, peerId);
        }

        ConversationDto actorView = buildConversationDto(actorId, conversationId);
        if (newlyCreated) {
            syncEvents.write(actorId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), actorView);
            ConversationDto peerView = buildConversationDto(peerId, conversationId);
            syncEvents.write(peerId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), peerView);
        }
        return actorView;
    }

    /**
     * Tạo nhóm chat mới: yêu cầu tên 1..100 ký tự, ít nhất 2 bạn bè ACCEPTED khác, tổng tối đa 50 thành viên.
     * Sinh tin SYSTEM GROUP_CREATED và CONVERSATION_UPDATED cho toàn bộ thành viên trong cùng transaction.
     */
    @Transactional
    public ConversationDto createGroup(UUID actorId, CreateGroupRequest req) {
        if (req == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Group request is required");
        }
        String cleanTitle = req.title() == null ? "" : req.title().trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Group title must be 1..100 characters");
        }
        if (req.memberIds() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "memberIds is required");
        }

        // Lọc trùng UUID và bỏ chính người tạo; bắt buộc có ít nhất 2 người khác và tổng <= 50.
        Set<UUID> distinctOthers = new LinkedHashSet<>();
        for (String raw : req.memberIds()) {
            UUID target = parseRequiredUuid(raw, "Invalid memberId");
            if (!actorId.equals(target)) {
                distinctOthers.add(target);
            }
        }
        if (distinctOthers.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Group requires at least 2 other members");
        }
        if (1 + distinctOthers.size() > MAX_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "Group cannot exceed 50 members");
        }

        // Sắp xếp cố định các cặp trước khi lockPair để tuyệt đối không xảy ra deadlock.
        List<UUID> sortedOthers = new ArrayList<>(distinctOthers);
        sortedOthers.sort(Comparator.comparing(UUID::toString));
        for (UUID target : sortedOthers) {
            policy.lockPair(actorId, target);
            if (!isActiveUser(target)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
            }
            if (policy.isBlockedEitherWay(actorId, target)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "Cannot add blocked user to group");
            }
            if (!policy.areAcceptedFriends(actorId, target)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "CONTACT_REQUIRED", "Only accepted friends can be added to a group");
            }
        }

        UUID conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (type, title, created_by) VALUES ('GROUP', ?, ?) RETURNING id",
                (rs, i) -> rs.getObject("id", UUID.class),
                cleanTitle, actorId);

        // Chèn người tạo với vai trò ADMIN và các thành viên khác với vai trò MEMBER (visible_from = 0).
        jdbc.update(
                "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                        + "VALUES (?, ?, 'ADMIN', 0)",
                conversationId, actorId);
        for (UUID target : sortedOthers) {
            jdbc.update(
                    "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                            + "VALUES (?, ?, 'MEMBER', 0)",
                    conversationId, target);
        }

        List<UUID> allMembers = new ArrayList<>(1 + sortedOthers.size());
        allMembers.add(actorId);
        allMembers.addAll(sortedOthers);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actorId", actorId.toString());
        params.put("title", cleanTitle);
        params.put("memberCount", allMembers.size());
        insertSystemMessage(conversationId, actorId, "GROUP_CREATED", params, allMembers);

        emitConversationUpdatedToMembers(conversationId, allMembers);
        return buildConversationDto(actorId, conversationId);
    }

    /**
     * Cập nhật tên nhóm (chỉ ADMIN) và/hoặc trạng thái tắt thông báo cá nhân (muted).
     * Lưu ý D-09: muted không bao giờ sinh tin SYSTEM hay sync_events; đổi trùng tên là no-op idempotent.
     */
    @Transactional
    public ConversationDto updateConversation(UUID actorId, UUID conversationId, UpdateConversationRequest req) {
        if (req == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Update payload is required");
        }
        LockedConversation conv = lockConversationOrThrow(conversationId);
        MemberState caller = loadMemberState(conversationId, actorId);
        if (caller == null || caller.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }

        // Thiết lập muted là cài đặt cá nhân của thành viên hiện tại, tuyệt đối không sinh tin SYSTEM.
        if (req.muted() != null && req.muted() != caller.muted) {
            jdbc.update(
                    "UPDATE conversation_members SET muted = ? WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                    req.muted(), conversationId, actorId);
        }

        if (req.title() != null) {
            if (!"GROUP".equals(conv.type)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Cannot rename a direct conversation");
            }
            if (!"ADMIN".equals(caller.role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ADMIN", "Only group admins can rename the group");
            }
            String cleanTitle = req.title().trim();
            if (cleanTitle.isEmpty() || cleanTitle.length() > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Group title must be 1..100 characters");
            }
            // Chỉ cập nhật và sinh SYSTEM GROUP_RENAMED khi tên nhóm thực sự thay đổi (D-09).
            if (!cleanTitle.equals(conv.title)) {
                jdbc.update("UPDATE conversations SET title = ? WHERE id = ?", cleanTitle, conversationId);
                List<UUID> activeMembers = loadActiveMemberIds(conversationId);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("actorId", actorId.toString());
                params.put("oldTitle", conv.title);
                params.put("newTitle", cleanTitle);
                insertSystemMessage(conversationId, actorId, "GROUP_RENAMED", params, activeMembers);
                emitConversationUpdatedToMembers(conversationId, activeMembers);
            }
        }

        return buildConversationDto(actorId, conversationId);
    }

    /**
     * Thêm thành viên (hoặc thêm lại thành viên cũ) vào nhóm (chỉ ADMIN, chỉ bạn bè ACCEPTED, tối đa 50 người).
     * Quan trọng D-14: lấy visibleCutoff = MAX(messages.id) TRƯỚC khi chèn tin SYSTEM MEMBER_ADDED
     * để thành viên mới/thêm lại chỉ thấy tin có id > visibleCutoff.
     */
    @Transactional
    public void addMembers(UUID actorId, UUID conversationId, AddMembersRequest req) {
        if (req == null || req.userIds() == null || req.userIds().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "userIds is required");
        }
        Set<UUID> requestedIds = new LinkedHashSet<>();
        for (String raw : req.userIds()) {
            requestedIds.add(parseRequiredUuid(raw, "Invalid userId"));
        }

        LockedConversation conv = lockConversationOrThrow(conversationId);
        if (!"GROUP".equals(conv.type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Cannot add members to a direct conversation");
        }
        MemberState caller = loadMemberState(conversationId, actorId);
        if (caller == null || caller.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this group");
        }
        if (!"ADMIN".equals(caller.role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ADMIN", "Only group admins can add members");
        }

        List<UUID> sortedTargets = new ArrayList<>(requestedIds);
        sortedTargets.sort(Comparator.comparing(UUID::toString));

        // Kiểm tra chính sách bạn bè cho mọi đối tượng (bỏ qua chính caller nếu lỡ gửi kèm).
        for (UUID target : sortedTargets) {
            if (actorId.equals(target)) {
                continue;
            }
            policy.lockPair(actorId, target);
            if (!isActiveUser(target)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found");
            }
            if (policy.isBlockedEitherWay(actorId, target)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "Cannot add blocked user to group");
            }
            if (!policy.areAcceptedFriends(actorId, target)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "CONTACT_REQUIRED", "Only accepted friends can be added to a group");
            }
        }

        Set<UUID> activeSet = new HashSet<>(loadActiveMemberIds(conversationId));
        List<UUID> newlyAdded = new ArrayList<>();
        for (UUID target : sortedTargets) {
            if (!activeSet.contains(target)) {
                newlyAdded.add(target);
            }
        }
        // Tất cả đối tượng đều đã ở trong nhóm -> no-op idempotent, không tạo thêm SYSTEM hay sync_events (D-09).
        if (newlyAdded.isEmpty()) {
            return;
        }

        if (activeSet.size() + newlyAdded.size() > MAX_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "Group cannot exceed 50 members");
        }

        // Chốt mốc visible_from_message_id TRƯỚC khi sinh tin MEMBER_ADDED để áp dụng dấu > chặt (D-14).
        Long maxId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM messages WHERE conversation_id = ?",
                Long.class,
                conversationId);
        long visibleCutoff = maxId == null ? 0L : maxId;

        for (UUID target : newlyAdded) {
            jdbc.update(
                    "INSERT INTO conversation_members "
                            + "(conversation_id, user_id, role, visible_from_message_id, last_delivered_message_id, last_read_message_id, muted, joined_at, left_at) "
                            + "VALUES (?, ?, 'MEMBER', ?, ?, ?, FALSE, now(), NULL) "
                            + "ON CONFLICT (conversation_id, user_id) DO UPDATE SET "
                            + "role = 'MEMBER', "
                            + "visible_from_message_id = GREATEST(conversation_members.visible_from_message_id, EXCLUDED.visible_from_message_id), "
                            + "last_delivered_message_id = GREATEST(conversation_members.last_delivered_message_id, EXCLUDED.visible_from_message_id), "
                            + "last_read_message_id = GREATEST(conversation_members.last_read_message_id, EXCLUDED.visible_from_message_id), "
                            + "joined_at = now(), "
                            + "left_at = NULL",
                    conversationId, target, visibleCutoff, visibleCutoff, visibleCutoff);
        }

        List<UUID> allActive = loadActiveMemberIds(conversationId);
        List<String> addedStrings = newlyAdded.stream().map(UUID::toString).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actorId", actorId.toString());
        params.put("targetUserIds", addedStrings);
        insertSystemMessage(conversationId, actorId, "MEMBER_ADDED", params, allActive);

        emitConversationUpdatedToMembers(conversationId, allActive);
    }

    /**
     * Xóa thành viên (ADMIN) hoặc tự rời nhóm (chính mình), bao phủ đủ 3 nhánh quản trị nguyên tử (D-10)
     * và gửi đúng 1 sự kiện CONVERSATION_UPDATED cuối cùng (leftAt != null) cho người rời (D-12).
     */
    @Transactional
    public void removeOrLeaveMember(UUID actorId, UUID conversationId, UUID targetUserId, String rawSuccessorUserId) {
        LockedConversation conv = lockConversationOrThrow(conversationId);
        if (!"GROUP".equals(conv.type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Cannot remove members from a direct conversation");
        }
        MemberState caller = loadMemberState(conversationId, actorId);
        if (caller == null || caller.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this group");
        }

        boolean selfLeave = actorId.equals(targetUserId);
        if (!selfLeave && !"ADMIN".equals(caller.role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ADMIN", "Only group admins can remove other members");
        }

        MemberState target = loadMemberState(conversationId, targetUserId);
        // Đối tượng không tồn tại hoặc đã rời trước đó -> no-op idempotent (D-09).
        if (target == null || target.left) {
            return;
        }

        List<MemberState> activeMembers = loadActiveMemberStates(conversationId);
        int activeCount = activeMembers.size();
        long adminCount = activeMembers.stream().filter(m -> "ADMIN".equals(m.role)).count();

        // Nhánh A (D-10): là ADMIN duy nhất và nhóm vẫn còn người ở lại -> bắt buộc bàn giao quyền trong cùng transaction.
        if (activeCount - 1 > 0 && "ADMIN".equals(target.role) && adminCount == 1) {
            if (rawSuccessorUserId == null || rawSuccessorUserId.isBlank()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ADMIN_HANDOVER_REQUIRED",
                        "Sole admin must select a successor before leaving");
            }
            UUID successorId = parseRequiredUuid(rawSuccessorUserId, "Invalid successorUserId");
            if (successorId.equals(targetUserId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Successor must be another active member");
            }
            boolean successorActive = activeMembers.stream().anyMatch(m -> m.userId.equals(successorId));
            if (!successorActive) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Successor must be an active group member");
            }

            jdbc.update(
                    "UPDATE conversation_members SET role = 'ADMIN' "
                            + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                    conversationId, successorId);

            // Chèn tin SYSTEM ROLE_CHANGED trước khi đánh dấu người cũ rời nhóm.
            List<UUID> currentActiveIds = activeMembers.stream().map(m -> m.userId).toList();
            Map<String, Object> roleParams = new LinkedHashMap<>();
            roleParams.put("actorId", actorId.toString());
            roleParams.put("targetUserId", successorId.toString());
            roleParams.put("newRole", "ADMIN");
            insertSystemMessage(conversationId, actorId, "ROLE_CHANGED", roleParams, currentActiveIds);
        }

        // Đánh dấu người rời/bị xóa bằng left_at = now().
        jdbc.update(
                "UPDATE conversation_members SET left_at = now() "
                        + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                conversationId, targetUserId);

        List<UUID> remainingActive = loadActiveMemberIds(conversationId);
        // Chỉ phát tin SYSTEM MEMBER_LEFT / MEMBER_REMOVED cho những thành viên còn lại (D-12).
        if (!remainingActive.isEmpty()) {
            String sysEvent = selfLeave ? "MEMBER_LEFT" : "MEMBER_REMOVED";
            Map<String, Object> leaveParams = new LinkedHashMap<>();
            leaveParams.put("actorId", actorId.toString());
            leaveParams.put("targetUserId", targetUserId.toString());
            insertSystemMessage(conversationId, actorId, sysEvent, leaveParams, remainingActive);
            emitConversationUpdatedToMembers(conversationId, remainingActive);
        }

        // Quy tắc bắt buộc D-12: gửi ĐÚNG 1 sự kiện CONVERSATION_UPDATED cuối cùng kèm leftAt != null cho người vừa rời.
        ConversationDto finalView = buildConversationDto(targetUserId, conversationId);
        syncEvents.write(targetUserId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), finalView);
    }

    /**
     * Thay đổi vai trò thành viên trong nhóm (ADMIN / MEMBER).
     * Chặn hạ quyền ADMIN duy nhất khi nhóm còn người khác (409 ADMIN_HANDOVER_REQUIRED); lặp vai trò là no-op.
     */
    @Transactional
    public void updateMemberRole(UUID actorId, UUID conversationId, UUID targetUserId, UpdateRoleRequest req) {
        if (req == null || req.role() == null || (!"ADMIN".equals(req.role()) && !"MEMBER".equals(req.role()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "role must be ADMIN or MEMBER");
        }
        LockedConversation conv = lockConversationOrThrow(conversationId);
        if (!"GROUP".equals(conv.type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "Cannot change roles in a direct conversation");
        }
        MemberState caller = loadMemberState(conversationId, actorId);
        if (caller == null || caller.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this group");
        }
        if (!"ADMIN".equals(caller.role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ADMIN", "Only group admins can update member roles");
        }

        MemberState target = loadMemberState(conversationId, targetUserId);
        if (target == null || target.left) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Target member not found");
        }
        // Đã ở đúng vai trò yêu cầu -> no-op idempotent (D-09).
        if (req.role().equals(target.role)) {
            return;
        }

        List<MemberState> activeMembers = loadActiveMemberStates(conversationId);
        long adminCount = activeMembers.stream().filter(m -> "ADMIN".equals(m.role)).count();
        if ("MEMBER".equals(req.role()) && "ADMIN".equals(target.role) && adminCount == 1 && activeMembers.size() > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ADMIN_HANDOVER_REQUIRED",
                    "Cannot demote the sole group admin");
        }

        jdbc.update(
                "UPDATE conversation_members SET role = ? WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                req.role(), conversationId, targetUserId);

        List<UUID> activeIds = activeMembers.stream().map(m -> m.userId).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actorId", actorId.toString());
        params.put("targetUserId", targetUserId.toString());
        params.put("newRole", req.role());
        insertSystemMessage(conversationId, actorId, "ROLE_CHANGED", params, activeIds);
        emitConversationUpdatedToMembers(conversationId, activeIds);
    }

    /**
     * Xử lý rời mọi nhóm đang tham gia khi tài khoản bị xóa vĩnh viễn sau 15 ngày chờ (AccountService.purge):
     * tự động nâng thành viên vào sớm nhất làm ADMIN kế nhiệm nếu userId là ADMIN duy nhất của nhóm.
     */
    void handleUserAccountPurge(UUID userId) {
        List<UUID> groupIds = jdbc.query(
                "SELECT c.id FROM conversations c "
                        + "JOIN conversation_members cm ON cm.conversation_id = c.id "
                        + "WHERE c.type = 'GROUP' AND cm.user_id = ? AND cm.left_at IS NULL "
                        + "ORDER BY c.id ASC",
                (rs, i) -> rs.getObject("id", UUID.class),
                userId);

        for (UUID groupId : groupIds) {
            lockConversationOrThrow(groupId);
            MemberState leaving = loadMemberState(groupId, userId);
            if (leaving == null || leaving.left) {
                continue;
            }
            List<MemberState> activeMembers = loadActiveMemberStates(groupId);
            long adminCount = activeMembers.stream().filter(m -> "ADMIN".equals(m.role)).count();

            // Nếu là ADMIN duy nhất và còn thành viên khác -> phong thành viên gia nhập sớm nhất làm ADMIN.
            if ("ADMIN".equals(leaving.role) && adminCount == 1 && activeMembers.size() > 1) {
                List<UUID> successors = jdbc.query(
                        "SELECT user_id FROM conversation_members "
                                + "WHERE conversation_id = ? AND user_id <> ? AND left_at IS NULL "
                                + "ORDER BY joined_at ASC, user_id ASC LIMIT 1",
                        (rs, i) -> rs.getObject("user_id", UUID.class),
                        groupId, userId);
                if (!successors.isEmpty()) {
                    UUID successorId = successors.get(0);
                    jdbc.update(
                            "UPDATE conversation_members SET role = 'ADMIN' "
                                    + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                            groupId, successorId);
                    List<UUID> remainingIds = activeMembers.stream()
                            .map(m -> m.userId)
                            .filter(id -> !id.equals(userId))
                            .toList();
                    Map<String, Object> roleParams = new LinkedHashMap<>();
                    roleParams.put("actorId", userId.toString());
                    roleParams.put("targetUserId", successorId.toString());
                    roleParams.put("newRole", "ADMIN");
                    insertSystemMessage(groupId, userId, "ROLE_CHANGED", roleParams, remainingIds);
                }
            }

            jdbc.update(
                    "UPDATE conversation_members SET left_at = now() "
                            + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                    groupId, userId);

            List<UUID> remainingActive = loadActiveMemberIds(groupId);
            if (!remainingActive.isEmpty()) {
                Map<String, Object> leaveParams = new LinkedHashMap<>();
                leaveParams.put("actorId", userId.toString());
                leaveParams.put("targetUserId", userId.toString());
                insertSystemMessage(groupId, userId, "MEMBER_LEFT", leaveParams, remainingActive);
                emitConversationUpdatedToMembers(groupId, remainingActive);
            }
        }
    }

    /**
     * Lấy danh sách các hội thoại mà người dùng đang là thành viên hoạt động (left_at IS NULL),
     * sắp xếp theo tin nhắn mới nhất giảm dần.
     */
    @Transactional(readOnly = true)
    public List<ConversationDto> listConversations(UUID actorId) {
        List<UUID> ids = jdbc.query(
                "SELECT c.id FROM conversations c "
                        + "JOIN conversation_members cm ON cm.conversation_id = c.id "
                        + "WHERE cm.user_id = ? AND cm.left_at IS NULL "
                        + "ORDER BY COALESCE(c.last_message_id, 0) DESC, c.created_at DESC",
                (rs, i) -> rs.getObject("id", UUID.class),
                actorId);
        List<ConversationDto> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            result.add(buildConversationDto(actorId, id));
        }
        return result;
    }

    /**
     * Lấy chi tiết một hội thoại; từ chối 404 nếu hội thoại không tồn tại và 403 NOT_A_MEMBER nếu không còn tham gia.
     */
    @Transactional(readOnly = true)
    public ConversationDto getConversation(UUID actorId, UUID conversationId) {
        if (!conversationExists(conversationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        MemberState member = loadMemberState(conversationId, actorId);
        if (member == null || member.left) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "Not a member of this conversation");
        }
        return buildConversationDto(actorId, conversationId);
    }

    /**
     * Dựng ConversationDto theo góc nhìn của viewerId (tái sử dụng cho Plan 05-03 SyncService),
     * áp dụng chặt bất đẳng thức m.id > visible_from_message_id cho lastMessage và unreadCount.
     */
    public ConversationDto buildConversationDto(UUID viewerId, UUID conversationId) {
        List<ConversationHeader> headers = jdbc.query(
                "SELECT c.id, c.type, c.title, c.avatar_url, c.last_message_id, c.created_at, "
                        + "cm.muted, cm.visible_from_message_id, cm.last_read_message_id, cm.left_at "
                        + "FROM conversations c "
                        + "JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.user_id = ? "
                        + "WHERE c.id = ?",
                (rs, i) -> new ConversationHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("avatar_url"),
                        rs.getObject("last_message_id") == null ? null : rs.getLong("last_message_id"),
                        toIso(rs.getTimestamp("created_at")),
                        rs.getBoolean("muted"),
                        rs.getLong("visible_from_message_id"),
                        rs.getLong("last_read_message_id"),
                        toIso(rs.getTimestamp("left_at"))),
                viewerId, conversationId);
        if (headers.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        ConversationHeader h = headers.get(0);

        List<MemberDto> members = jdbc.query(
                "SELECT cm.user_id, u.display_name, u.avatar_url, cm.role, cm.visible_from_message_id, "
                        + "cm.last_delivered_message_id, cm.last_read_message_id, cm.muted, cm.left_at "
                        + "FROM conversation_members cm "
                        + "JOIN users u ON u.id = cm.user_id "
                        + "WHERE cm.conversation_id = ? "
                        + "ORDER BY cm.joined_at ASC, cm.user_id ASC",
                (rs, i) -> new MemberDto(
                        rs.getObject("user_id", UUID.class).toString(),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getString("role"),
                        rs.getLong("visible_from_message_id"),
                        rs.getLong("last_delivered_message_id"),
                        rs.getLong("last_read_message_id"),
                        rs.getBoolean("muted"),
                        toIso(rs.getTimestamp("left_at"))),
                conversationId);

        // Chỉ hiển thị lastMessage nếu nằm sau mốc visible_from_message_id (dấu > chặt).
        Long visibleLastMessageId = null;
        MessageDto lastMessage = null;
        if (h.lastMessageId != null && h.lastMessageId > h.visibleFromMessageId) {
            visibleLastMessageId = h.lastMessageId;
            lastMessage = loadMessageById(h.lastMessageId);
        }

        // Số tin chưa đọc chỉ tính các tin của người khác lớn hơn cả last_read và visible_from.
        long unreadFloor = Math.max(h.lastReadMessageId, h.visibleFromMessageId);
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM messages m "
                        + "WHERE m.conversation_id = ? AND m.sender_id <> ? AND m.id > ?",
                Integer.class,
                conversationId, viewerId, unreadFloor);

        return new ConversationDto(
                h.id.toString(),
                h.type,
                h.title,
                h.avatarUrl,
                visibleLastMessageId,
                h.createdAt,
                unread == null ? 0 : unread,
                h.muted,
                h.visibleFromMessageId,
                h.leftAt,
                lastMessage,
                members);
    }

    /**
     * Chèn tin nhắn hệ thống SYSTEM vào bảng messages, cập nhật last_message_id và phát MESSAGE_CREATED
     * cho toàn bộ người nhận hoạt động trong cùng transaction (D-09).
     */
    private MessageDto insertSystemMessage(
            UUID conversationId,
            UUID actorId,
            String systemEvent,
            Map<String, Object> params,
            Collection<UUID> recipients) {
        String paramsJson;
        try {
            paramsJson = json.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode systemParams", e);
        }

        Long msgId = jdbc.queryForObject(
                "INSERT INTO messages (conversation_id, sender_id, client_id, type, system_event, system_params) "
                        + "VALUES (?, ?, ?, 'SYSTEM', ?, ?) RETURNING id",
                Long.class,
                conversationId, actorId, UUID.randomUUID(), systemEvent, paramsJson);

        jdbc.update(
                "UPDATE conversations SET last_message_id = GREATEST(COALESCE(last_message_id, 0), ?) WHERE id = ?",
                msgId, conversationId);
        jdbc.update(
                "UPDATE conversation_members "
                        + "SET last_delivered_message_id = GREATEST(last_delivered_message_id, ?), "
                        + "    last_read_message_id = GREATEST(last_read_message_id, ?) "
                        + "WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                msgId, msgId, conversationId, actorId);

        MessageDto dto = loadMessageById(msgId);
        for (UUID recipient : recipients) {
            syncEvents.write(recipient, "MESSAGE_CREATED", "MESSAGE", String.valueOf(msgId), dto);
        }
        return dto;
    }

    /** Phát sự kiện CONVERSATION_UPDATED theo đúng góc nhìn của từng thành viên hoạt động. */
    private void emitConversationUpdatedToMembers(UUID conversationId, Collection<UUID> memberIds) {
        for (UUID memberId : memberIds) {
            ConversationDto view = buildConversationDto(memberId, conversationId);
            syncEvents.write(memberId, "CONVERSATION_UPDATED", "CONVERSATION", conversationId.toString(), view);
        }
    }

    /** Khóa dòng conversations bằng FOR UPDATE để tuần tự hóa toàn bộ thao tác thành viên và tin nhắn nhóm. */
    private LockedConversation lockConversationOrThrow(UUID conversationId) {
        List<LockedConversation> rows = jdbc.query(
                "SELECT id, type, title FROM conversations WHERE id = ? FOR UPDATE",
                (rs, i) -> new LockedConversation(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("title")),
                conversationId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        return rows.get(0);
    }

    private MemberState loadMemberState(UUID conversationId, UUID userId) {
        List<MemberState> rows = jdbc.query(
                "SELECT user_id, role, muted, (left_at IS NOT NULL) AS left_flag "
                        + "FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                (rs, i) -> new MemberState(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("role"),
                        rs.getBoolean("muted"),
                        rs.getBoolean("left_flag")),
                conversationId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<MemberState> loadActiveMemberStates(UUID conversationId) {
        return jdbc.query(
                "SELECT user_id, role, muted, FALSE AS left_flag "
                        + "FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL "
                        + "ORDER BY joined_at ASC, user_id ASC",
                (rs, i) -> new MemberState(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("role"),
                        rs.getBoolean("muted"),
                        false),
                conversationId);
    }

    private List<UUID> loadActiveMemberIds(UUID conversationId) {
        return jdbc.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL "
                        + "ORDER BY joined_at ASC, user_id ASC",
                (rs, i) -> rs.getObject("user_id", UUID.class),
                conversationId);
    }

    /** Kiểm tra chính sách nhắn tin 1-1: 403 BLOCKED luôn được ưu tiên trước 403 CONTACT_REQUIRED. */
    void enforceDirectPolicy(UUID actorId, UUID peerId) {
        if (policy.isBlockedEitherWay(actorId, peerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "User is blocked");
        }
        Boolean allowStrangers = jdbc.queryForObject(
                "SELECT allow_messages_from_non_contacts FROM users WHERE id = ?",
                Boolean.class,
                peerId);
        if (!Boolean.TRUE.equals(allowStrangers) && !policy.areAcceptedFriends(actorId, peerId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CONTACT_REQUIRED",
                    "Recipient only accepts messages from accepted contacts");
        }
    }

    private void ensureDirectMembers(UUID conversationId, UUID actorId, UUID peerId) {
        jdbc.update(
                "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                        + "VALUES (?, ?, 'MEMBER', 0) ON CONFLICT (conversation_id, user_id) DO NOTHING",
                conversationId, actorId);
        jdbc.update(
                "INSERT INTO conversation_members (conversation_id, user_id, role, visible_from_message_id) "
                        + "VALUES (?, ?, 'MEMBER', 0) ON CONFLICT (conversation_id, user_id) DO NOTHING",
                conversationId, peerId);
    }

    private boolean isActiveUser(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND status = 'ACTIVE')",
                Boolean.class,
                userId));
    }

    private boolean conversationExists(UUID conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM conversations WHERE id = ?)",
                Boolean.class,
                conversationId));
    }

    private MessageDto loadMessageById(long messageId) {
        List<MessageDto> rows = jdbc.query(
                MessageService.SELECT_MESSAGE_WITH_METADATA + " WHERE m.id = ?",
                MessageService::mapMessageRow,
                messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String buildDirectKey(UUID a, UUID b) {
        String sa = a.toString().toLowerCase();
        String sb = b.toString().toLowerCase();
        return sa.compareTo(sb) <= 0 ? sa + ":" + sb : sb + ":" + sa;
    }

    static UUID parseRequiredUuid(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
        }
    }

    static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private record LockedConversation(UUID id, String type, String title) {
    }

    private record MemberState(UUID userId, String role, boolean muted, boolean left) {
    }

    private record ConversationHeader(
            UUID id,
            String type,
            String title,
            String avatarUrl,
            Long lastMessageId,
            String createdAt,
            boolean muted,
            long visibleFromMessageId,
            long lastReadMessageId,
            String leftAt) {
    }
}
