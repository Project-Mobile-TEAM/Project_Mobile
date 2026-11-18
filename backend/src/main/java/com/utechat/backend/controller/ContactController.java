package com.utechat.backend.controller;

import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ContactsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Tám endpoint danh bạ/lời mời dùng actor JWT; quyền và transaction được kiểm tại service. */
@RestController
@RequestMapping("/api")
public class ContactController {
    private final ContactsService service;
    public ContactController(ContactsService service) { this.service=service; }
    /** Actor lấy từ JWT; thiếu phone để service trả cùng mã PHONE_INVALID như đầu vào sai. */
    @GetMapping("/users/lookup") public UserLookupResponse lookup(@AuthenticationPrincipal AuthenticatedActor a,@RequestParam(name="phone",required=false) String phone) { return service.lookup(a.userId(),phone); }
    /** Chỉ nhận addresseeId trong body; các trường giả mạo người gửi không được dùng làm danh tính. */
    @PostMapping("/contact-requests") @ResponseStatus(HttpStatus.CREATED)
    public ContactRequestDto send(@AuthenticationPrincipal AuthenticatedActor a,@RequestBody SendContactRequestBody body) { return service.send(a.userId(),ContactsService.addresseeId(body==null?null:body.addresseeId())); }
    /** Không nhận ID chủ danh sách từ client; filter chỉ chọn chiều và trạng thái trong tài khoản JWT. */
    @GetMapping("/contact-requests") public List<ContactRequestDto> requests(@AuthenticationPrincipal AuthenticatedActor a,@RequestParam(name="status",required=false) String status,@RequestParam(name="type",required=false) String type) { return service.requests(a.userId(),status,type); }
    /** Người nhận lấy từ JWT; service giữ transaction và kiểm lại trạng thái/chặn trước khi chấp nhận. */
    @PostMapping("/contact-requests/{id}/accept") public ContactRequestDto accept(@AuthenticationPrincipal AuthenticatedActor a,@PathVariable(name="id") String id) { return service.accept(a.userId(),ContactsService.id(id)); }
    /** Chỉ người nhận được reject; service kiểm vai trò và trạng thái trong cùng transaction. */
    @PostMapping("/contact-requests/{id}/reject") public ContactRequestDto reject(@AuthenticationPrincipal AuthenticatedActor a,@PathVariable(name="id") String id) { return service.respond(a.userId(),ContactsService.id(id),"REJECTED"); }
    /** DELETE hủy lời mời chỉ dành cho người gửi; thành công trả 204, không có response body. */
    @DeleteMapping("/contact-requests/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedActor a,@PathVariable(name="id") String id) { service.respond(a.userId(),ContactsService.id(id),"CANCELED"); }
    /** Danh sách theo chủ sở hữu JWT, chỉ lấy dòng bạn bè không bị chủ tài khoản chặn. */
    @GetMapping("/contacts") public List<ContactDto> friends(@AuthenticationPrincipal AuthenticatedActor a) { return service.contacts(a.userId(),false); }
    /** Unfriend tác động hai chiều nhưng giữ quyết định chặn; actor luôn lấy từ JWT. */
    @DeleteMapping("/contacts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfriend(@AuthenticationPrincipal AuthenticatedActor a,@PathVariable(name="id") String id) { service.unfriend(a.userId(),ContactsService.id(id)); }
}
