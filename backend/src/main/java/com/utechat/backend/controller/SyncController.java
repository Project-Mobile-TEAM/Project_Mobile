package com.utechat.backend.controller;

import com.utechat.backend.dto.MessagingDtos.BootstrapResponse;
import com.utechat.backend.dto.MessagingDtos.SyncResponse;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.SyncService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho khởi tạo snapshot (/api/sync/bootstrap) và đồng bộ bù (/api/sync?after=&limit=).
 */
@RestController
@RequestMapping("/api")
public class SyncController {
    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /** Tải snapshot ban đầu nhất quán (hồ sơ, hội thoại, danh bạ, lời mời PENDING) kèm syncCursor. */
    @GetMapping("/sync/bootstrap")
    public BootstrapResponse bootstrap(@AuthenticationPrincipal AuthenticatedActor actor) {
        return syncService.bootstrap(actor.userId());
    }

    /** Tải phân trang các sự kiện sync_events mới hơn con trỏ after (lọc barrier_xid an toàn). */
    @GetMapping("/sync")
    public SyncResponse sync(
            @AuthenticationPrincipal AuthenticatedActor actor,
            @RequestParam("after") long after,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return syncService.sync(actor.userId(), after, limit);
    }
}
