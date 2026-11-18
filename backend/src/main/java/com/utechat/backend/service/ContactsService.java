package com.utechat.backend.service;

import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Một ranh giới transaction cho quan hệ: khóa cặp trước, kiểm quyền rồi mới thay đổi dữ liệu. */
@Service
public class ContactsService {
    private final JdbcTemplate db;
    private final ContactPolicyService policy;
    private final LookupRateLimiter limiter;
    private final SyncEventWriter events;
    private final TransactionTemplate tx;
    private static final String REQUEST_SELECT = "SELECT r.*, a.display_name an, a.avatar_url av, b.display_name bn, b.avatar_url bv "
            + "FROM contact_requests r JOIN users a ON a.id=r.requester_id JOIN users b ON b.id=r.addressee_id ";
    public ContactsService(JdbcTemplate db, ContactPolicyService policy, LookupRateLimiter limiter,
            SyncEventWriter events, PlatformTransactionManager manager) {
        this.db=db; this.policy=policy; this.limiter=limiter; this.events=events; this.tx=new TransactionTemplate(manager);
    }
    /** UUID từ URL sai định dạng không làm lộ chi tiết truy vấn. */
    public static UUID id(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException e) { throw error(HttpStatus.NOT_FOUND,"NOT_FOUND"); }
    }
    /** Body gửi lời mời phải chứa UUID đầy đủ; không nhận dạng rút gọn hoặc suy actor từ client. */
    public static UUID addresseeId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw error(HttpStatus.BAD_REQUEST, "VALIDATION");
        }
    }
    private static ApiException error(HttpStatus status,String code) { return new ApiException(status,code,code); }
    private void active(UUID user) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND status='ACTIVE')",Boolean.class,user)))
            throw error(HttpStatus.NOT_FOUND,"NOT_FOUND");
    }
    private void pair(UUID a,UUID b) {
        if (a.equals(b)) throw error(HttpStatus.BAD_REQUEST,"SELF");
        policy.lockPair(a,b); active(a); active(b);
    }
    private void notBlocked(UUID a,UUID b) { if(policy.isBlockedEitherWay(a,b)) throw error(HttpStatus.FORBIDDEN,"BLOCKED"); }

    /** Chỉ tìm chính xác; gộp người không tồn tại, ẩn hồ sơ và chặn thành cùng lỗi NOT_FOUND. */
    public UserLookupResponse lookup(UUID actor,String phone) {
        if(phone==null || !phone.matches("^\\+[1-9][0-9]{7,14}$")) throw error(HttpStatus.BAD_REQUEST,"PHONE_INVALID");
        limiter.checkAndRecord(actor);
        // Chỉ đọc ID để xác định cặp; sau khi lấy lock mới đọc hồ sơ và trạng thái được phép trả.
        var rows=db.queryForList("SELECT id FROM users WHERE phone_e164=?",phone);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var u=rows.get(0); UUID target=(UUID)u.get("id");
        if(actor.equals(target)) throw error(HttpStatus.BAD_REQUEST,"SELF");
        return tx.execute(status -> {
            // Dùng cùng khóa cặp với thay đổi quan hệ, tránh ghép hồ sơ trước và trạng thái sau một block.
            pair(actor, target);
            var profiles = db.queryForList("SELECT display_name,avatar_url,status,discoverable FROM users WHERE id=? AND phone_e164=?", target, phone);
            if (profiles.isEmpty()) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND");
            var profile = profiles.get(0);
            if (!"ACTIVE".equals(profile.get("status")) || !Boolean.TRUE.equals(profile.get("discoverable"))
                    || policy.isBlockedEitherWay(actor, target)) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND");
            var pending = pending(actor, target);
            boolean friend = policy.areAcceptedFriends(actor, target);
            String direction = pending == null ? null : (pending.requester().id().equals(actor.toString()) ? "OUTGOING" : "INCOMING");
            return new UserLookupResponse(target.toString(), (String) profile.get("display_name"), (String) profile.get("avatar_url"), friend,
                    pending != null, friend ? "FRIEND" : direction == null ? "STRANGER" : direction + "_PENDING",
                    pending == null ? null : pending.id(), direction);
        });
    }
    private ContactRequestDto mapRequest(ResultSet r,int row) throws SQLException {
        return new ContactRequestDto(r.getString("id"), new UserSummaryDto(r.getString("requester_id"),r.getString("an"),r.getString("av")),
                new UserSummaryDto(r.getString("addressee_id"),r.getString("bn"),r.getString("bv")),r.getString("status"),
                r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("responded_at")==null?null:r.getTimestamp("responded_at").toInstant().toString());
    }
    private ContactRequestDto request(UUID requestId,boolean lock) {
        var rows=db.query(REQUEST_SELECT+"WHERE r.id=?"+(lock?" FOR UPDATE OF r":""),this::mapRequest,requestId);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"NOT_FOUND");
        return rows.get(0);
    }
    private ContactRequestDto pending(UUID a,UUID b) {
        var rows=db.query(REQUEST_SELECT+"WHERE r.status='PENDING' AND ((r.requester_id=? AND r.addressee_id=?) OR (r.requester_id=? AND r.addressee_id=?))",this::mapRequest,a,b,b,a);
        return rows.isEmpty()?null:rows.get(0);
    }
    private ApiException alreadyPending(UUID actor,ContactRequestDto r) {
        return new ApiException(HttpStatus.CONFLICT,"REQUEST_ALREADY_PENDING","Request already pending",Map.of(
                "existingRequestId",r.id(),"direction",r.requester().id().equals(actor.toString())?"OUTGOING":"INCOMING"));
    }
    private void requestEvent(ContactRequestDto r,String type) {
        for(String user:List.of(r.requester().id(),r.addressee().id())) events.write(id(user),type,"CONTACT_REQUEST",r.id(),r);
    }
    /** Gửi ngược chiều không tự kết bạn; bắt lỗi unique sau khi transaction đã rollback. */
    public ContactRequestDto send(UUID actor,UUID target) {
        try { return tx.execute(s -> {
            pair(actor,target); notBlocked(actor,target);
            if(policy.areAcceptedFriends(actor,target)) throw error(HttpStatus.CONFLICT,"ALREADY_FRIENDS");
            var existing=pending(actor,target); if(existing!=null) throw alreadyPending(actor,existing);
            UUID key=db.queryForObject("INSERT INTO contact_requests(requester_id,addressee_id) VALUES (?,?) RETURNING id",UUID.class,actor,target);
            // Sự kiện hai bên dùng cùng transaction; lỗi ở bất kỳ lần ghi nào phải hủy cả lời mời.
            var r=request(key,false); requestEvent(r,"CONTACT_REQUEST_CREATED"); return r;
        }); } catch(DuplicateKeyException e) {
            var existing=pending(actor,target); if(existing!=null) throw alreadyPending(actor,existing); throw e;
        }
    }
}
