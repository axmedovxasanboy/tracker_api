package uz.tracker.trackerproject.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.response.CategoryBreakdownResponse;
import uz.tracker.trackerproject.dto.response.DashboardSummaryResponse;
import uz.tracker.trackerproject.dto.response.MonthlyDataResponse;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.service.TransactionService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final TransactionService transactionService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            @RequestParam(defaultValue = "USD") Currency currency
    ) {
        return ResponseEntity.ok(transactionService.getSummary(currency));
    }

    @GetMapping("/monthly")
    public ResponseEntity<List<MonthlyDataResponse>> getMonthly(
            @RequestParam(defaultValue = "USD") Currency currency,
            @RequestParam(required = false) Integer year
    ) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(transactionService.getMonthlyData(currency, targetYear));
    }

    @GetMapping("/category-breakdown")
    public ResponseEntity<List<CategoryBreakdownResponse>> getCategoryBreakdown(
            @RequestParam(defaultValue = "EXPENSE") TransactionType type,
            @RequestParam(defaultValue = "USD") Currency currency,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        return ResponseEntity.ok(transactionService.getCategoryBreakdown(type, currency, year, month));
    }
}
