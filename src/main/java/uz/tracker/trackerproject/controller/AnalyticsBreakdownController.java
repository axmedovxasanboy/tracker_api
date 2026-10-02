package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tracker.trackerproject.dto.response.AnalyticsBreakdownResponse;
import uz.tracker.trackerproject.service.AnalyticsBreakdownService;
import uz.tracker.trackerproject.service.LevelService;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Analytics V2's one read (ANALYTICS-V2-SPEC §4.1). Authenticated like every other /api/v1 route.
 * Beside {@link AnalyticsController}, which stays as it is until the V2 web has replaced its page.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsBreakdownController {

    private final AnalyticsBreakdownService service;
    /** A start or an end of Level 5 due today is recorded before the figures are read; null when built by hand. */
    private final LevelService levelService;

    /**
     * Everything the six Analytics pages show for the months {@code from}..{@code to} (YYYY-MM,
     * inclusive) as of {@code date} (the owner's local day, YYYY-MM-DD; today when omitted). {@code to}
     * defaults to the month of {@code date} and {@code from} to {@code to}. Anything unparseable, a
     * range that ends after the month of {@code date}, runs backwards or is longer than 24 months is a
     * 400 — the rules and words of {@code GET /analytics}. Writes nothing.
     */
    @GetMapping("/breakdown")
    public ResponseEntity<AnalyticsBreakdownResponse> breakdown(@RequestParam(required = false) String from,
                                                                @RequestParam(required = false) String to,
                                                                @RequestParam(required = false) String date) {
        LocalDate day = AnalyticsController.parseDate(date);
        YearMonth fromMonth = AnalyticsController.parseMonth("from", from);
        YearMonth toMonth = AnalyticsController.parseMonth("to", to);
        if (levelService != null) levelService.refreshQuietly(day);
        return ResponseEntity.ok(service.breakdown(fromMonth, toMonth, day));
    }
}
