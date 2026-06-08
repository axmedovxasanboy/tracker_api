package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.ResetRequest;
import uz.tracker.trackerproject.dto.request.SettingsRequest;
import uz.tracker.trackerproject.dto.response.SettingsResponse;
import uz.tracker.trackerproject.dto.response.TelegramConfigResponse;
import uz.tracker.trackerproject.service.ResetService;
import uz.tracker.trackerproject.service.SettingsService;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService service;
    private final ResetService resetService;

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

    /**
     * DANGER ZONE — factory reset. Re-verifies the current account password, then wipes
     * all data (account + settings included) and re-seeds defaults. After this the app is
     * back to first-run state and the caller's tokens are dead, so the client must send the
     * user to signup. Authenticated by default (not in {@code SecurityConfig}'s permitAll).
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest req, Authentication auth) {
        resetService.reset(auth.getName(), req.getPassword());
        return ResponseEntity.noContent().build();
    }
}
