package com.utechat.backend.controller;

import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ContactsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Bốn endpoint tra cứu, gửi/xem/chấp nhận lời mời dùng actor từ JWT; service kiểm quyền. */
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
}
