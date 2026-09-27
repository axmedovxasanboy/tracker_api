package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.CashBalanceRequest;
import uz.tracker.trackerproject.dto.request.CurrentCashRequest;
import uz.tracker.trackerproject.dto.response.CashBalanceResponse;
import uz.tracker.trackerproject.dto.response.CurrentCashResponse;
import uz.tracker.trackerproject.service.CashBalanceService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cash-balances")
@RequiredArgsConstructor
public class CashBalanceController {

    private final CashBalanceService service;

    @GetMapping
    public ResponseEntity<List<CashBalanceResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    /** Upsert — same shape as create. Convenient for the inline edit on the Cards page. */
    @PostMapping
    public ResponseEntity<CashBalanceResponse> upsert(@Valid @RequestBody CashBalanceRequest req) {
        return ResponseEntity.ok(service.upsert(req));
    }

    /**
     * "Update cash": the cash held NOW. The difference from the app's figure is booked as everyday
     * spending (less) or a surplus (more); the starting cash stays. POST / and PUT / still set the
     * starting cash.
     */
    @PostMapping("/current")
    public ResponseEntity<CurrentCashResponse> setCurrent(@Valid @RequestBody CurrentCashRequest req) {
        return ResponseEntity.ok(service.setCurrent(req));
    }

    @PutMapping
    public ResponseEntity<CashBalanceResponse> update(@Valid @RequestBody CashBalanceRequest req) {
        return ResponseEntity.ok(service.upsert(req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
