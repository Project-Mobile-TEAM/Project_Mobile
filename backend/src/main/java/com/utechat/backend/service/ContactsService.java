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
    /** Chỉ lấy lời mời có actor tham gia; filter được kiểm danh sách cho phép trước khi tạo SQL. */
    public List<ContactRequestDto> requests(UUID actor,String status,String type) {
        String state=status==null||status.isBlank()?"PENDING":status;
        if(!List.of("PENDING","ACCEPTED","REJECTED","CANCELED").contains(state) ||
                (type!=null&&!type.isBlank()&&!List.of("incoming","outgoing").contains(type))) throw error(HttpStatus.BAD_REQUEST,"VALIDATION");
        String direction="incoming".equals(type)?"r.addressee_id=?":"outgoing".equals(type)?"r.requester_id=?":"(r.requester_id=? OR r.addressee_id=?)";
        Object[] args=type==null||type.isBlank()?new Object[]{state,actor,actor}:new Object[]{state,actor};
        // Một snapshot SQL cho cả hai hồ sơ; ID là khóa phụ để thứ tự ổn định khi trùng thời gian.
        return db.query(REQUEST_SELECT+"WHERE r.status=? AND "+direction+" AND a.status='ACTIVE' AND b.status='ACTIVE' ORDER BY r.created_at DESC,r.id DESC",this::mapRequest,args);
    }

    /** Chỉ người nhận chấp nhận; lấy khóa cặp trước khóa dòng và không phục hồi bạn từ lịch sử ACCEPTED. */
    public ContactRequestDto accept(UUID actor, UUID key) {
        return tx.execute(status -> {
            // Tái sử dụng truy vấn nháp để tìm cặp bất biến, chưa khóa dòng ở bước đọc sơ bộ.
            var preliminary = request(key, false);
            UUID sender = id(preliminary.requester().id()), recipient = id(preliminary.addressee().id());
            if (!actor.equals(recipient)) throw error(HttpStatus.FORBIDDEN, "FORBIDDEN");
            pair(sender, recipient);
            var current = request(key, true);
            notBlocked(sender, recipient);
            if ("ACCEPTED".equals(current.status())) return current;
            if (!"PENDING".equals(current.status())) throw error(HttpStatus.CONFLICT, "INVALID_STATE");
            db.update("UPDATE contact_requests SET status='ACCEPTED',responded_at=now() WHERE id=?", key);
            UUID lo = sender.compareTo(recipient) < 0 ? sender : recipient;
            UUID hi = lo.equals(sender) ? recipient : sender;
            for (UUID owner : List.of(lo, hi)) {
                UUID other = owner.equals(sender) ? recipient : sender;
                // Giữ created_at của dòng đã có; không ghi event nếu cờ bạn bè vốn không đổi.
                int changed = db.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,TRUE,FALSE) "
                        + "ON CONFLICT(owner_id,contact_user_id) DO UPDATE SET is_friend=TRUE WHERE NOT contacts.is_friend", owner, other);
                if (changed > 0) acceptedContactEvent(owner, other);
            }
            var updated = request(key, false);
            requestEvent(updated, "CONTACT_REQUEST_UPDATED");
            return updated;
        });
    }

    /** Đọc payload theo chiều chủ sở hữu ngay trong transaction accept, không phụ thuộc API danh sách mục 08. */
    private void acceptedContactEvent(UUID owner, UUID other) {
        ContactDto user = db.queryForObject("SELECT u.id,u.phone_e164,u.display_name,u.avatar_url,c.blocked,c.created_at "
                + "FROM contacts c JOIN users u ON u.id=c.contact_user_id WHERE c.owner_id=? AND c.contact_user_id=?",
                (row, index) -> new ContactDto(row.getString("id"), row.getString("phone_e164"), row.getString("display_name"),
                        row.getString("avatar_url"), row.getBoolean("blocked"), row.getTimestamp("created_at").toInstant().toString()), owner, other);
        events.write(owner, "CONTACT_UPDATED", "CONTACT", other.toString(), Map.of(
                "contactUserId", other.toString(), "isFriend", true, "blocked", false, "removed", false, "user", user));
    }
    /** Đọc cặp bất biến để tìm khóa; sau khi khóa phải đọc lại trạng thái và kiểm đúng vai trò. */
    public ContactRequestDto respond(UUID actor,UUID key,String next) {
        // Giữ chữ ký nháp cũ nhưng dùng chung accept đã nghiệm thu, không giữ hai nhánh tạo bạn khác nhau.
        if ("ACCEPTED".equals(next)) return accept(actor, key);
        if (!"REJECTED".equals(next) && !"CANCELED".equals(next)) throw error(HttpStatus.BAD_REQUEST, "VALIDATION");
        return tx.execute(s -> {
            var preliminary=request(key,false);
            UUID a=id(preliminary.requester().id()), b=id(preliminary.addressee().id());
            // Không cho người thứ ba giữ khóa cặp của hai người khác bằng một request trái quyền.
            if(!actor.equals("CANCELED".equals(next)?a:b)) throw error(HttpStatus.FORBIDDEN,"FORBIDDEN");
            pair(a,b); var r=request(key,true);
            if(next.equals(r.status())) return r; // Lặp thao tác không ghi thêm quan hệ hoặc sự kiện.
            if(!"PENDING".equals(r.status())) throw error(HttpStatus.CONFLICT,"INVALID_STATE");
            db.update("UPDATE contact_requests SET status=?,responded_at=now() WHERE id=?",next,key);
            // Reject/cancel vẫn được phép khi có block; không tạo hoặc xóa quan hệ contact tại đây.
            var updated=request(key,false); requestEvent(updated,"CONTACT_REQUEST_UPDATED"); return updated;
        });
    }
    /** Danh sách phản ánh dòng do chủ tài khoản sở hữu; quyền tương tác luôn kiểm chặn cả hai chiều. */
    public List<ContactDto> contacts(UUID actor,boolean blocked) {
        return db.query("SELECT u.id,CASE WHEN c.is_friend THEN u.phone_e164 ELSE NULL END phone,u.display_name,u.avatar_url,c.blocked,c.created_at "
                +"FROM contacts c JOIN users u ON u.id=c.contact_user_id WHERE c.owner_id=? AND c.blocked=? "
                +"AND (c.blocked OR c.is_friend) AND u.status='ACTIVE' ORDER BY u.display_name,u.id", (r,i)->new ContactDto(r.getString("id"),r.getString("phone"),
                r.getString("display_name"),r.getString("avatar_url"),r.getBoolean("blocked"),r.getTimestamp("created_at").toInstant().toString()),actor,blocked);
    }
    /** Payload thể hiện trạng thái sau thay đổi, không chỉ lệnh thao tác; giữ riêng isFriend để không mất thông tin. */
    private void contactEvent(UUID owner,UUID other) {
        var rows=db.queryForList("SELECT is_friend,blocked FROM contacts WHERE owner_id=? AND contact_user_id=?",owner,other);
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("contactUserId",other.toString()); payload.put("removed",rows.isEmpty());
        payload.put("blocked",!rows.isEmpty()&&Boolean.TRUE.equals(rows.get(0).get("blocked")));
        payload.put("isFriend",!rows.isEmpty()&&Boolean.TRUE.equals(rows.get(0).get("is_friend")));
        if(!rows.isEmpty()) contacts(owner,Boolean.TRUE.equals(rows.get(0).get("blocked"))).stream().filter(c->c.id().equals(other.toString())).findFirst().ifPresent(c->payload.put("user",c));
        events.write(owner,"CONTACT_UPDATED","CONTACT",other.toString(),payload);
    }
    /** Chặn thuộc chiều mình; giữ cờ bạn cũ và hủy pending hai chiều trong cùng transaction/sự kiện. */
    public void block(UUID actor,UUID target) {
        tx.executeWithoutResult(s -> {
            pair(actor,target);
            int changed=db.update("INSERT INTO contacts(owner_id,contact_user_id,is_friend,blocked) VALUES (?,?,FALSE,TRUE) "
                    +"ON CONFLICT(owner_id,contact_user_id) DO UPDATE SET blocked=TRUE WHERE NOT contacts.blocked",actor,target);
            var pending=pending(actor,target);
            if(pending!=null) { db.update("UPDATE contact_requests SET status='CANCELED',responded_at=now() WHERE id=?",id(pending.id())); requestEvent(request(id(pending.id()),false),"CONTACT_REQUEST_UPDATED"); }
            if(changed>0) contactEvent(actor,target);
        });
    }
    /** Bỏ block-only bằng xóa dòng; bạn còn hiệu lực chỉ hạ blocked, không suy bạn từ lời mời lịch sử. */
    public void unblock(UUID actor,UUID target) {
        tx.executeWithoutResult(s -> {
            pair(actor,target);
            int removed=db.update("DELETE FROM contacts WHERE owner_id=? AND contact_user_id=? AND blocked AND NOT is_friend",actor,target);
            int changed=db.update("UPDATE contacts SET blocked=FALSE WHERE owner_id=? AND contact_user_id=? AND blocked AND is_friend",actor,target);
            if(removed+changed>0) contactEvent(actor,target);
        });
    }
    /** Hủy bạn không được xóa quyết định chặn; phát sự kiện cả khi chỉ thay đổi is_friend trên dòng chặn. */
    public void unfriend(UUID actor,UUID target) {
        tx.executeWithoutResult(s -> {
            pair(actor,target);
            // Cùng thứ tự chủ dòng như accept, dù hai người gửi unfriend ngược chiều đồng thời.
            UUID lo=actor.compareTo(target)<0?actor:target, hi=lo.equals(actor)?target:actor;
            for(UUID owner:List.of(lo,hi)) {
                UUID other=owner.equals(actor)?target:actor;
                int removed=db.update("DELETE FROM contacts WHERE owner_id=? AND contact_user_id=? AND NOT blocked",owner,other);
                int changed=db.update("UPDATE contacts SET is_friend=FALSE WHERE owner_id=? AND contact_user_id=? AND blocked AND is_friend",owner,other);
                if(removed+changed>0) contactEvent(owner,other);
            }
        });
    }
}
