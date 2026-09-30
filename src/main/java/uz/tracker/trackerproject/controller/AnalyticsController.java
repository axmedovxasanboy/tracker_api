package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tracker.trackerproject.dto.response.AnalyticsResponse;
import uz.tracker.trackerproject.service.AnalyticsService;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Authenticated like every other /api/v1 route (SecurityConfig: anyRequest().authenticated()). */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService service;

    /**
     * The Analytics page's figures for the months {@code from}..{@code to} (YYYY-MM, inclusive), as of
     * {@code date} (the owner's local day, YYYY-MM-DD; today when omitted). {@code to} defaults to the
     * month of {@code date} and {@code from} to {@code to}. A range that ends after the month of
     * {@code date}, runs backwards, or is longer than 24 months is a 400.
     */
    @GetMapping
    public ResponseEntity<AnalyticsResponse> analytics(@RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String date) {
        return ResponseEntity.ok(service.analytics(parseMonth("from", from), parseMonth("to", to), parseDate(date)));
    }

    static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD, got: " + date);
        }
    }

    static YearMonth parseMonth(String name, String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be YYYY-MM, got: " + month);
        }
    }
}
