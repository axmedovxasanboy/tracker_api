package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.dto.response.TelegramConfigResponse;
import uz.tracker.trackerproject.service.SettingsService;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService service;

    @GetMapping
    public ResponseEntity<SettingsResponse> get() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping
    public ResponseEntity<SettingsResponse> update(@Valid @RequestBody SettingsRequest req) {
        return ResponseEntity.ok(service.update(req));
    }

    /**
     * Public, non-secret Telegram config for the bot to read at startup (no session yet).
     * Whitelisted in {@code SecurityConfig}.
     */
    @GetMapping("/telegram")
    public ResponseEntity<TelegramConfigResponse> telegram() {
        return ResponseEntity.ok(service.getTelegramConfig());
    }
}
