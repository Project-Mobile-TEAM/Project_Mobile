package com.utechat.backend.controller;

import com.utechat.backend.dto.ContactDtos.*;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.ContactsService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Tra cứu và gửi lời mời dùng actor từ JWT; kiểm quyền nằm trong service. */
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
}
