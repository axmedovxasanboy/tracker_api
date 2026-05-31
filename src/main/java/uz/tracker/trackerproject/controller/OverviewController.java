package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.LevelConfigRequest;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse;
import uz.tracker.trackerproject.dto.response.BucketPayment;
import uz.tracker.trackerproject.dto.response.OverviewIncomeResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.service.OverviewService;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/overview")
@RequiredArgsConstructor
public class OverviewController {

    private final OverviewService service;

    @GetMapping("/income")
    public ResponseEntity<OverviewIncomeResponse> getIncome(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.getIncome(parseMonth(month), currency));
    }

    @GetMapping("/tier")
    public ResponseEntity<OverviewTierResponse> getTier(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.getTier(parseMonth(month), currency));
    }

    @GetMapping("/allocation-ledger")
    public ResponseEntity<AllocationLedgerResponse> getAllocationLedger(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.getAllocationLedger(parseMonth(month), currency));
    }

    @GetMapping("/allocation-rules")
    public ResponseEntity<AllocationRulesViewResponse> getAllocationRules() {
        return ResponseEntity.ok(service.getAllocationRules());
    }

    @PutMapping("/level-config")
    public ResponseEntity<AllocationRulesViewResponse> saveLevelConfig(
            @RequestBody LevelConfigRequest req) {
        return ResponseEntity.ok(service.saveLevelConfig(req));
    }

    @GetMapping("/bucket/{bucket}/payments")
    public ResponseEntity<List<BucketPayment>> getBucketPayments(
            @PathVariable String bucket,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "UZS") Currency currency) {
        return ResponseEntity.ok(service.getBucketPayments(bucket, parseMonth(month), currency));
    }

    /** Accepts "YYYY-MM"; missing/blank → current month. Bad format → 400 from caller. */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be in YYYY-MM format (got: " + month + ")");
        }
    }
}
