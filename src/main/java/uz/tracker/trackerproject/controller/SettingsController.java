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

    /**
     * {@code date} (optional, YYYY-MM-DD): the owner's local day, as {@code /advisor} takes it — the
     * server's clock is UTC; its month is "the current month". Default: the server's today.
     */
    @GetMapping
    public ResponseEntity<SettingsResponse> get(@RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.get(parseDate(date)));
    }

    @PutMapping
    public ResponseEntity<SettingsResponse> update(@Valid @RequestBody SettingsRequest req,
                                                   @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.update(req, parseDate(date)));
    }

    /**
     * Remove the monthly income recorded from {@code month} (YYYY-MM): that month falls back to the
     * entry before it. 400 for the only remaining entry or a bad month, 404 when none is recorded from it.
     */
    @DeleteMapping("/stable-income/{month}")
    public ResponseEntity<SettingsResponse> deleteStableIncome(@PathVariable String month,
                                                               @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.deleteStableIncome(month, parseDate(date)));
    }

    private static java.time.LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return java.time.LocalDate.now();
        try {
            return java.time.LocalDate.parse(date.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD, got: " + date);
        }
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
