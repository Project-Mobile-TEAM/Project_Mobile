package com.utechat.backend.controller;

import com.utechat.backend.dto.StickerDtos.StickerAsset;
import com.utechat.backend.dto.StickerDtos.StickerPackDto;
import com.utechat.backend.security.AuthenticatedActor;
import com.utechat.backend.service.StickerService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API bộ nhãn dán. SecurityConfig yêu cầu Bearer cho cả danh mục và ảnh. */
@RestController
@RequestMapping("/api/stickers")
public class StickerController {
    private final StickerService stickers;

    public StickerController(StickerService stickers) {
        this.stickers = stickers;
    }

    @GetMapping("/packs")
    public List<StickerPackDto> list(@AuthenticationPrincipal AuthenticatedActor actor) {
        return stickers.listPacks(actor.userId());
    }

    @PostMapping("/packs/{packId}/install")
    public ResponseEntity<Void> install(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String packId) {
        stickers.setInstalled(actor.userId(), ConversationController.parsePathUuid(packId), true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/packs/{packId}/install")
    public ResponseEntity<Void> uninstall(@AuthenticationPrincipal AuthenticatedActor actor, @PathVariable String packId) {
        stickers.setInstalled(actor.userId(), ConversationController.parsePathUuid(packId), false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assets/{packCode}/{fileName}")
    public ResponseEntity<byte[]> asset(@PathVariable String packCode, @PathVariable String fileName) {
        StickerAsset asset = stickers.loadAsset(packCode, fileName);
        // Ảnh dùng cache riêng của thiết bị và giữ đúng loại MIME từ danh mục.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(asset.bytes());
    }
}
