package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.MonthCloseRequest;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse;
import uz.tracker.trackerproject.dto.response.MonthCloseResponse;
import uz.tracker.trackerproject.dto.response.MonthSummaryResponse;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.service.MonthCloseService;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/months")
@RequiredArgsConstructor
public class MonthCloseController {

    private final MonthCloseService service;

    /** The monthly-envelope summary: earned / tagged / spent / left for the month. */
    @GetMapping("/summary")
    public ResponseEntity<MonthSummaryResponse> summary(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.getMonthSummary(parseMonth(month), currency));
    }

    /** Reconciliation preview for a month: per-wallet computed balances + the month's envelope figures. */
    @GetMapping("/preview")
    public ResponseEntity<MonthClosePreviewResponse> preview(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.preview(parseMonth(month), currency));
    }

    /** Commit a permanent month close (books per-wallet everyday-spend adjustments + locks the month). */
    @PostMapping("/close")
    public ResponseEntity<MonthCloseResponse> close(@Valid @RequestBody MonthCloseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.close(req));
    }

    /** All closed months, newest first. */
    @GetMapping
    public ResponseEntity<List<MonthCloseResponse>> list() {
        return ResponseEntity.ok(service.listClosed());
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be in YYYY-MM format (got: " + month + ")");
        }
    }
}
