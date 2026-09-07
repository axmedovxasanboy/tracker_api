package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.EmergencyRequest;
import uz.tracker.trackerproject.dto.response.EmergencyResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.Emergency;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.EmergencyRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmergencyService {

    private final EmergencyRepository repo;
    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final CategoryRepository categoryRepository;
    private final CardService cardService;
    private final SettingsService settingsService;
    private final MonthCloseService monthCloseService;

    /**
     * Contributions to list. A blank month keeps the all-time list; a YYYY-MM scopes it to the
     * month, so the tab's "N contributions · total" headline can be made to agree with the
     * Emergency bucket figure for the same month instead of quoting an all-time sum beside it.
     */
    @Transactional(readOnly = true)
    public List<EmergencyResponse> getAll(String month) {
        if (month == null || month.isBlank()) {
            return repo.findAllByOrderByDateDesc().stream().map(EmergencyResponse::from).toList();
        }
        YearMonth ym = parseMonth(month);
        return repo.findByDateBetweenOrderByDateDesc(ym.atDay(1), ym.atEndOfMonth()).stream()
                .map(EmergencyResponse::from).toList();
    }

    @Transactional
    public EmergencyResponse create(EmergencyRequest req) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(req.getDate());
        // Mirror to an EXPENSE Transaction so the contribution shows up in the
        // Transactions list AND in the per-bucket payment history alongside any
        // emergency contributions created from the other side.
        Transaction tx = new Transaction();
        tx.setType(TransactionType.EXPENSE);
        tx.setSubType(TransactionSubType.EMERGENCY_CONTRIBUTION);
        // Every bucket-funding row carries the bucket it credits, so no read has to re-derive one.
        tx.setAllocationBucket(
                AllocationBucket.forSubType(TransactionSubType.EMERGENCY_CONTRIBUTION, false));
        tx.setAmount(req.getAmount());
        tx.setCurrency(req.getCurrency());
        tx.setDescription(description(req));
        tx.setTransactionDate(req.getDate());
        if (req.getCardId() != null) {
            Card card = cardRepository.findById(req.getCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getCardId()));
            if (card.getCurrency() != req.getCurrency()) {
                throw new IllegalArgumentException(
                        "Card currency (" + card.getCurrency() + ") does not match payment currency (" + req.getCurrency() + ")");
            }
            cardService.assertSufficientBalance(card, req.getAmount());
            tx.setCard(card);
            tx.setCashAmount(BigDecimal.ZERO);
        } else {
            tx.setCard(null);
            tx.setCashAmount(req.getAmount());
        }
        // Category: explicit override wins; else auto-pick the single category for this sub-type.
        if (req.getCategoryId() != null) {
            categoryRepository.findById(req.getCategoryId()).ifPresent(tx::setCategory);
        } else {
            List<Category> matches = categoryRepository.findByApplicableSubTypeAndParentIsNull(TransactionSubType.EMERGENCY_CONTRIBUTION);
            if (matches.size() == 1) tx.setCategory(matches.get(0));
        }
        Transaction saved = transactionRepository.save(tx);

        Emergency e = new Emergency();
        apply(e, req);
        // The transaction IS the money; this row is only the tab's copy of it. Remembering which
        // transaction it mirrors is what lets an edit or a delete move both together.
        e.setOriginatingTransactionId(saved.getId());
        return EmergencyResponse.from(repo.save(e));
    }

    /**
     * Edit a contribution. The mirrored transaction is edited with it — without that, changing the
     * amount here left the Emergency bucket, the wallet balance and the Transactions list all
     * showing the old figure, and only this tab showing the new one.
     */
    @Transactional
    public EmergencyResponse update(Long id, EmergencyRequest req) {
        Emergency e = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Emergency", id));
        // Both the month it sits in and the month it would move to must be open, exactly as for a
        // transaction edit — the mirror below is a real wallet movement in one of them.
        monthCloseService.assertMonthOpen(e.getDate());
        monthCloseService.assertMonthOpen(req.getDate());
        mirrorTransaction(e).ifPresent(tx -> {
            Card card = tx.getCard();
            if (card != null) {
                if (card.getCurrency() != req.getCurrency()) {
                    throw new IllegalArgumentException(
                            "Card currency (" + card.getCurrency() + ") does not match payment currency (" + req.getCurrency() + ")");
                }
                // Growing the amount spends more from the same wallet, so re-check the difference.
                cardService.assertSufficientBalance(card, req.getAmount().subtract(tx.getAmount()));
            }
            tx.setAmount(req.getAmount());
            tx.setCurrency(req.getCurrency());
            tx.setTransactionDate(req.getDate());
            tx.setDescription(description(req));
            // Cash rows carry the full amount in cashAmount (the buildTransaction convention);
            // card rows keep it at zero.
            tx.setCashAmount(card == null ? req.getAmount() : BigDecimal.ZERO);
            transactionRepository.save(tx);
        });
        apply(e, req);
        return EmergencyResponse.from(repo.save(e));
    }

    /**
     * Delete a contribution and the transaction it mirrors, so the money returns to the wallet and
     * the Emergency bucket drops by the same amount. Deleting the row alone used to leave the
     * bucket credit standing forever.
     */
    @Transactional
    public void delete(Long id) {
        Emergency e = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Emergency", id));
        monthCloseService.assertMonthOpen(e.getDate());
        mirrorTransaction(e).ifPresent(transactionRepository::delete);
        repo.delete(e);
    }

    /**
     * The transaction this row mirrors, when it has one. Legacy rows predate the link and simply
     * have no mirror to move — they are edited list-only rather than refused.
     */
    private Optional<Transaction> mirrorTransaction(Emergency e) {
        return e.getOriginatingTransactionId() == null
                ? Optional.empty()
                : transactionRepository.findById(e.getOriginatingTransactionId());
    }

    private static String description(EmergencyRequest req) {
        return req.getDescription() != null && !req.getDescription().isBlank()
                ? req.getDescription() : "Emergency fund contribution";
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("month must be in YYYY-MM format (got: " + month + ")");
        }
    }

    private void apply(Emergency e, EmergencyRequest req) {
        e.setAmount(req.getAmount());
        e.setCurrency(req.getCurrency());
        e.setDate(req.getDate());
        e.setDescription(req.getDescription());
    }
}
