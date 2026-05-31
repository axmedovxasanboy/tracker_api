package uz.tracker.trackerproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tracker.trackerproject.dto.request.BalanceTransferRequest;
import uz.tracker.trackerproject.dto.request.BulkTransactionRequest;
import uz.tracker.trackerproject.dto.request.ExchangeRequest;
import uz.tracker.trackerproject.dto.request.TransactionRequest;
import uz.tracker.trackerproject.dto.response.PageResponse;
import uz.tracker.trackerproject.dto.response.TransactionResponse;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.service.TransactionService;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<PageResponse<TransactionResponse>> getAll(
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Currency currency,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long cardId,
            @RequestParam(required = false) Long investmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "transactionDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "false") boolean excludeTransfers,
            @RequestParam(defaultValue = "false") boolean cashOnly
    ) {
        return ResponseEntity.ok(transactionService.getAll(
                type, currency, categoryId, cardId, investmentId, startDate, endDate, search,
                page, size, sortBy, sortDir, excludeTransfers, cashOnly
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(transactionService.getById(id));
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.create(request));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<TransactionResponse>> createBulk(@Valid @RequestBody BulkTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createBulk(request.getCardId(), request.getTransactions()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody TransactionRequest request
    ) {
        return ResponseEntity.ok(transactionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfer")
    public ResponseEntity<List<TransactionResponse>> transfer(@Valid @RequestBody BalanceTransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.transferBalance(request));
    }

    @PostMapping("/exchange")
    public ResponseEntity<List<TransactionResponse>> exchange(@Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.exchange(request));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<List<String>> getSuggestions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "") String q
    ) {
        return ResponseEntity.ok(transactionService.getDescriptionSuggestions(categoryId, q));
    }

    @GetMapping("/places")
    public ResponseEntity<List<String>> getPlaceSuggestions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "") String q
    ) {
        return ResponseEntity.ok(transactionService.getPlaceSuggestions(categoryId, q));
    }
}
