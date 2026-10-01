package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.LevelRulesRequest;
import uz.tracker.trackerproject.dto.response.LevelNoticeResponse;
import uz.tracker.trackerproject.dto.response.LevelsResponse;
import uz.tracker.trackerproject.service.LevelService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Levels and their savings rules (LEVELS-ALLOCATION-SPEC §3). Authenticated like every other
 * /api/v1 route. {@code date} (optional, YYYY-MM-DD) is the owner's local day, as {@code /advisor}
 * takes it — the server's clock is UTC; its month is "the current month". Default: the server's today.
 */
@RestController
@RequestMapping("/api/v1/levels")
@RequiredArgsConstructor
public class LevelController {

    private final LevelService service;

    @GetMapping
    public ResponseEntity<LevelsResponse> levels(@RequestParam(required = false) String date) {
        LocalDate day = parseDate(date);
        service.refreshQuietly(day);
        return ResponseEntity.ok(service.levels(day));
    }

    @PutMapping("/{level}/rules")
    public ResponseEntity<LevelsResponse> saveRules(@PathVariable String level, @RequestBody LevelRulesRequest req,
                                                    @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.saveRules(level, req, parseDate(date)));
    }

    @DeleteMapping("/{level}/rules/{month}")
    public ResponseEntity<LevelsResponse> deleteRules(@PathVariable String level, @PathVariable String month,
                                                      @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.deleteRules(level, month, parseDate(date)));
    }

    /** 200 with the oldest change {@code client} (WEB | BOT) has not seen; 204 when there is none. */
    @GetMapping("/notice")
    public ResponseEntity<LevelNoticeResponse> notice(@RequestParam(required = false) String client,
                                                      @RequestParam(required = false) String date) {
        service.refreshQuietly(parseDate(date));
        return service.notice(client).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/notice/{id}/seen")
    public ResponseEntity<Void> seen(@PathVariable Long id, @RequestParam(required = false) String client) {
        service.seen(id, client);
        return ResponseEntity.noContent().build();
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD, got: " + date);
        }
    }
}
