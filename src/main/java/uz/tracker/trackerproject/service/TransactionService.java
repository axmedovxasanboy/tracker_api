package uz.tracker.trackerproject.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.*;
import uz.tracker.trackerproject.dto.response.*;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.*;
import uz.tracker.trackerproject.exception.ResourceNotFoundException;
import uz.tracker.trackerproject.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CardRepository cardRepository;
    private final CashBalanceRepository cashBalanceRepository;
    private final FinanceService financeService;
    private final LoanGivenRepository loanGivenRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DonationRepository donationRepository;
    private final InvestmentRepository investmentRepository;

    @Value("${app.pagination.max-page-size:100}")
    private int maxPageSize;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "transactionDate", "amount", "createdAt", "description");

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getAll(
            TransactionType type, Currency currency, Long categoryId, Long cardId,
            Long investmentId,
            LocalDate startDate, LocalDate endDate, String search,
            int page, int size, String sortBy, String sortDir,
            boolean excludeTransfers, boolean cashOnly
    ) {
        if (size <= 0) size = 20;
        if (size > maxPageSize) size = maxPageSize;
        if (page < 0) page = 0;
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) sortBy = "transactionDate";

        // Tie-break on id in the same direction so same-date rows have a deterministic
        // order — without this, pagination can duplicate or skip rows at page boundaries.
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending().and(Sort.by("id").ascending())
                : Sort.by(sortBy).descending().and(Sort.by("id").descending());
        PageRequest pageable = PageRequest.of(page, size, sort);
        var spec = TransactionSpecification.withFilters(
                type, currency, categoryId, cardId, investmentId,
                startDate, endDate, search, excludeTransfers, cashOnly);
        Page<Transaction> result = transactionRepository.findAll(spec, pageable);
        return PageResponse.from(result.map(TransactionResponse::from));
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id) {
        return TransactionResponse.from(findOrThrow(id));
    }

    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        validateCashAmount(request);
        validateCurrencyMatchesCard(request.getCardId(), request.getCurrency());
        checkCardBalance(request.getCardId(), cardPortionOf(request), request.getType(), null);
        Transaction transaction = buildTransaction(new Transaction(), request);
        Transaction saved = transactionRepository.save(transaction);
        autoCreateFinanceRecord(request, saved.getId());
        return TransactionResponse.from(saved);
    }

    @Transactional
    public List<TransactionResponse> createBulk(Long cardId, List<TransactionRequest> requests) {
        List<TransactionResponse> out = new ArrayList<>(requests.size());
        for (TransactionRequest req : requests) {
            if (cardId != null) req.setCardId(cardId);
            validateCashAmount(req);
            validateCurrencyMatchesCard(req.getCardId(), req.getCurrency());
            checkCardBalance(req.getCardId(), cardPortionOf(req), req.getType(), null);
            Transaction saved = transactionRepository.save(buildTransaction(new Transaction(), req));
            autoCreateFinanceRecord(req, saved.getId());
            out.add(TransactionResponse.from(saved));
        }
        return out;
    }

    @Transactional
    public TransactionResponse update(Long id, TransactionRequest request) {
        Transaction existing = findOrThrow(id);
        validateCashAmount(request);
        validateCurrencyMatchesCard(request.getCardId(), request.getCurrency());
        checkCardBalance(request.getCardId(), cardPortionOf(request), request.getType(), existing);

        BigDecimal previousAmount = existing.getAmount();
        TransactionSubType previousSubType = existing.getSubType();
        Long previousInvestmentId = existing.getInvestmentId();

        Transaction transaction = buildTransaction(existing, request);
        Transaction saved = transactionRepository.save(transaction);

        syncFinanceRecordOnUpdate(saved, previousAmount, previousSubType, previousInvestmentId, request);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        reverseFinanceRecordOnDelete(tx);

        // If this is half of a transfer pair, also delete the other half.
        if (tx.getTransferPairId() != null) {
            transactionRepository.findFirstByTransferPairIdAndIdNot(tx.getTransferPairId(), id)
                    .ifPresent(transactionRepository::delete);
        }

        transactionRepository.delete(tx);
    }

    @Transactional(readOnly = true)
    public List<String> getDescriptionSuggestions(Long categoryId, String query) {
        if (query == null || query.isBlank()) return List.of();
        return transactionRepository.findDescriptionSuggestions(categoryId, query.trim());
    }

    @Transactional(readOnly = true)
    public List<String> getPlaceSuggestions(Long categoryId, String query) {
        return transactionRepository.findDistinctPlaces(categoryId, query == null ? "" : query.trim());
    }

    @Transactional
    public List<TransactionResponse> transferBalance(BalanceTransferRequest request) {
        if (request.getFromCardId().equals(request.getToCardId())) {
            throw new IllegalArgumentException("Source and destination cards must be different");
        }
        Card fromCard = cardRepository.findById(request.getFromCardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", request.getFromCardId()));
        Card toCard = cardRepository.findById(request.getToCardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", request.getToCardId()));

        if (fromCard.getCurrency() != toCard.getCurrency()) {
            throw new IllegalArgumentException(
                    "Cannot transfer between cards with different currencies (" +
                            fromCard.getCurrency() + " → " + toCard.getCurrency() +
                            "). Currency conversion is not supported yet.");
        }

        checkCardBalance(request.getFromCardId(), request.getAmount(), TransactionType.EXPENSE, null);

        String desc = (request.getDescription() != null && !request.getDescription().isBlank())
                ? request.getDescription() : "Balance transfer";

        Transaction expense = new Transaction();
        expense.setType(TransactionType.EXPENSE);
        expense.setAmount(request.getAmount());
        expense.setCurrency(fromCard.getCurrency());
        expense.setCard(fromCard);
        expense.setDescription(desc);
        expense.setTransactionDate(request.getTransactionDate());
        expense.setSubType(TransactionSubType.TRANSFER_OUT);
        expense.setNote("Transfer to " + toCard.getName() + " (•••• " + toCard.getLastFourDigits() + ")");

        Transaction income = new Transaction();
        income.setType(TransactionType.INCOME);
        income.setAmount(request.getAmount());
        income.setCurrency(toCard.getCurrency());
        income.setCard(toCard);
        income.setDescription(desc);
        income.setTransactionDate(request.getTransactionDate());
        income.setSubType(TransactionSubType.TRANSFER_IN);
        income.setNote("Transfer from " + fromCard.getName() + " (•••• " + fromCard.getLastFourDigits() + ")");

        Transaction savedExpense = transactionRepository.save(expense);
        Transaction savedIncome = transactionRepository.save(income);

        // Link the two with a shared pair id (the expense's persisted id) so deletes can cascade.
        savedExpense.setTransferPairId(savedExpense.getId());
        savedIncome.setTransferPairId(savedExpense.getId());
        transactionRepository.save(savedExpense);
        transactionRepository.save(savedIncome);

        return List.of(TransactionResponse.from(savedExpense), TransactionResponse.from(savedIncome));
    }

    // ── Exchange (cash ↔ card, cross-currency allowed) ────────────────────────

    /**
     * Cross-wallet / cross-currency exchange. Either side can be a card or "cash"
     * (cardId = null). When cross-currency, fromAmount and toAmount may differ — the
     * implied FX rate is `fromAmount / toAmount`. We persist two rows:
     *   – EXPENSE row on the source side, sub-type EXCHANGE_OUT
     *   – INCOME row on the destination side, sub-type EXCHANGE_IN
     * They share transferPairId so deletes cascade just like a Transfer.
     *
     * Cash-side rows have cardId = null and book the cashAmount as the full amount,
     * keeping the cash-balance math consistent with regular cash transactions.
     */
    @Transactional
    public List<TransactionResponse> exchange(ExchangeRequest req) {
        if (req.getFromAmount() == null || req.getFromAmount().signum() <= 0)
            throw new IllegalArgumentException("Amount sent must be greater than 0");
        if (req.getToAmount() == null || req.getToAmount().signum() <= 0)
            throw new IllegalArgumentException("Amount received must be greater than 0");
        if (req.getFromCurrency() == null || req.getToCurrency() == null)
            throw new IllegalArgumentException("Both source and destination currency are required");

        Card fromCard = null;
        if (req.getFromCardId() != null) {
            fromCard = cardRepository.findById(req.getFromCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getFromCardId()));
            if (fromCard.getCurrency() != req.getFromCurrency()) {
                throw new IllegalArgumentException(
                        "Source card currency (" + fromCard.getCurrency() + ") does not match source currency (" + req.getFromCurrency() + ")");
            }
        }
        Card toCard = null;
        if (req.getToCardId() != null) {
            toCard = cardRepository.findById(req.getToCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getToCardId()));
            if (toCard.getCurrency() != req.getToCurrency()) {
                throw new IllegalArgumentException(
                        "Destination card currency (" + toCard.getCurrency() + ") does not match destination currency (" + req.getToCurrency() + ")");
            }
        }
        if (fromCard != null && toCard != null && fromCard.getId().equals(toCard.getId())) {
            throw new IllegalArgumentException("Source and destination cannot be the same card");
        }
        if (req.getFromCardId() == null && req.getToCardId() == null
                && req.getFromCurrency() == req.getToCurrency()) {
            throw new IllegalArgumentException("Cannot exchange cash to cash in the same currency — there's nothing to exchange");
        }

        // Pre-flight balance check for the source card (if any).
        checkCardBalance(req.getFromCardId(), req.getFromAmount(), TransactionType.EXPENSE, null);

        String baseDesc = (req.getDescription() != null && !req.getDescription().isBlank())
                ? req.getDescription()
                : (req.getFromCurrency() + " → " + req.getToCurrency() + " exchange");

        Transaction outRow = new Transaction();
        outRow.setType(TransactionType.EXPENSE);
        outRow.setSubType(TransactionSubType.EXCHANGE_OUT);
        outRow.setAmount(req.getFromAmount());
        outRow.setCurrency(req.getFromCurrency());
        outRow.setCard(fromCard);
        outRow.setCashAmount(fromCard == null ? req.getFromAmount() : BigDecimal.ZERO);
        outRow.setDescription(baseDesc);
        outRow.setTransactionDate(req.getTransactionDate());
        outRow.setNote(buildExchangeNote(req, false));

        Transaction inRow = new Transaction();
        inRow.setType(TransactionType.INCOME);
        inRow.setSubType(TransactionSubType.EXCHANGE_IN);
        inRow.setAmount(req.getToAmount());
        inRow.setCurrency(req.getToCurrency());
        inRow.setCard(toCard);
        inRow.setCashAmount(toCard == null ? req.getToAmount() : BigDecimal.ZERO);
        inRow.setDescription(baseDesc);
        inRow.setTransactionDate(req.getTransactionDate());
        inRow.setNote(buildExchangeNote(req, true));

        Transaction savedOut = transactionRepository.save(outRow);
        Transaction savedIn = transactionRepository.save(inRow);
        savedOut.setTransferPairId(savedOut.getId());
        savedIn.setTransferPairId(savedOut.getId());
        transactionRepository.save(savedOut);
        transactionRepository.save(savedIn);

        return List.of(TransactionResponse.from(savedOut), TransactionResponse.from(savedIn));
    }

    private String buildExchangeNote(ExchangeRequest req, boolean incomingSide) {
        String src = req.getFromCardId() != null ? "card" : "cash";
        String dst = req.getToCardId() != null ? "card" : "cash";
        // Implied rate, only when meaningful (different currencies).
        if (req.getFromCurrency() != req.getToCurrency()) {
            BigDecimal rate = req.getFromAmount().divide(req.getToAmount(), 6, RoundingMode.HALF_UP);
            return String.format("%s %s %s → %s %s %s · rate %s %s/%s",
                    req.getFromAmount(), req.getFromCurrency(), src,
                    req.getToAmount(), req.getToCurrency(), dst,
                    rate, req.getFromCurrency(), req.getToCurrency());
        }
        return String.format("%s → %s (same currency)", src, dst);
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(Currency currency) {
        BigDecimal totalIncome  = nullToZero(transactionRepository.sumByTypeAndCurrency(TransactionType.INCOME, currency));
        BigDecimal totalExpense = nullToZero(transactionRepository.sumByTypeAndCurrency(TransactionType.EXPENSE, currency));
        long count = transactionRepository.countByCurrency(currency);
        BigDecimal available = computeAvailableBalance(currency);
        return DashboardSummaryResponse.builder()
                .currency(currency)
                .totalIncome(totalIncome)
                .totalExpense(totalExpense)
                // Net Balance reflects overall standing (initial balances + every flow),
                // not just the period's income minus expense. Spending 300K UZS with 5M
                // already in the wallet should still read as a Surplus.
                .netBalance(available)
                .transactionCount(count)
                .availableBalance(available)
                .build();
    }

    /**
     * Sum of every wallet's current balance in the given currency.
     *   – Each Card: initialBalance + cardRepository.sumTransactionsByCardId
     *   – Cash:      cashBalance.initialBalance + cashBalanceRepository.sumCashlessTransactions
     * If no CashBalance row exists for the currency, the cash contribution is just the
     * cardless tx delta (initial = 0).
     */
    private BigDecimal computeAvailableBalance(Currency currency) {
        BigDecimal total = BigDecimal.ZERO;
        for (Card card : cardRepository.findAll()) {
            if (card.getCurrency() != currency) continue;
            BigDecimal initial = nullToZero(card.getInitialBalance());
            BigDecimal delta = nullToZero(cardRepository.sumTransactionsByCardId(card.getId()));
            total = total.add(initial).add(delta);
        }
        BigDecimal cashInitial = cashBalanceRepository.findByCurrency(currency)
                .map(cb -> nullToZero(cb.getInitialBalance()))
                .orElse(BigDecimal.ZERO);
        BigDecimal cashDelta = nullToZero(cashBalanceRepository.sumCashlessTransactions(currency));
        return total.add(cashInitial).add(cashDelta);
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<MonthlyDataResponse> getMonthlyData(Currency currency, int year) {
        List<Object[]> raw = entityManager.createNativeQuery("""
                SELECT EXTRACT(MONTH FROM transaction_date)::int,
                       COALESCE(SUM(CASE WHEN type = 'INCOME'  THEN amount ELSE 0::numeric END), 0::numeric),
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0::numeric END), 0::numeric)
                FROM transactions
                WHERE currency = :currency
                  AND EXTRACT(YEAR FROM transaction_date) = :year
                  AND (sub_type IS NULL OR sub_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT', 'EXCHANGE_IN', 'EXCHANGE_OUT'))
                GROUP BY EXTRACT(MONTH FROM transaction_date)
                ORDER BY EXTRACT(MONTH FROM transaction_date)
                """)
                .setParameter("currency", currency.name())
                .setParameter("year", year)
                .getResultList();

        Map<Integer, Object[]> byMonth = raw.stream()
                .collect(Collectors.toMap(r -> ((Number) r[0]).intValue(), r -> r));

        List<MonthlyDataResponse> result = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            Object[] row = byMonth.get(m);
            BigDecimal income = row != null ? toBigDecimal(row[1]) : BigDecimal.ZERO;
            BigDecimal expense = row != null ? toBigDecimal(row[2]) : BigDecimal.ZERO;
            result.add(MonthlyDataResponse.builder()
                    .month(m)
                    .monthName(Month.of(m).name().substring(0, 3))
                    .income(income)
                    .expense(expense)
                    .net(income.subtract(expense))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<CategoryBreakdownResponse> getCategoryBreakdown(
            TransactionType type, Currency currency, Integer year, Integer month
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.name, c.color, COALESCE(SUM(t.amount), 0::numeric)
                FROM transactions t
                JOIN categories c ON c.id = t.category_id
                WHERE t.type = :type AND t.currency = :currency
                  AND (t.sub_type IS NULL OR t.sub_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT'))
                """);
        if (year != null)  sql.append(" AND EXTRACT(YEAR  FROM t.transaction_date) = :year");
        if (month != null) sql.append(" AND EXTRACT(MONTH FROM t.transaction_date) = :month");
        sql.append(" GROUP BY c.id, c.name, c.color ORDER BY SUM(t.amount) DESC");

        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("type", type.name())
                .setParameter("currency", currency.name());
        if (year  != null) query.setParameter("year",  year);
        if (month != null) query.setParameter("month", month);

        List<Object[]> raw = query.getResultList();
        BigDecimal total = raw.stream().map(r -> toBigDecimal(r[2])).reduce(BigDecimal.ZERO, BigDecimal::add);

        return raw.stream().map(r -> {
            BigDecimal amount = toBigDecimal(r[2]);
            double pct = total.compareTo(BigDecimal.ZERO) == 0 ? 0
                    : amount.divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
            return CategoryBreakdownResponse.builder()
                    .category((String) r[0])
                    .color(r[1] != null ? (String) r[1] : "#6366f1")
                    .amount(amount)
                    .percentage(Math.round(pct * 100.0) / 100.0)
                    .build();
        }).toList();
    }

    // ── Finance auto-creation ─────────────────────────────────────────────────

    private boolean isAnonymousCategory(Long categoryId) {
        if (categoryId == null) return false;
        return categoryRepository.findById(categoryId)
                .map(c -> Boolean.TRUE.equals(c.getAnonymizes())
                        || (c.getParent() != null && Boolean.TRUE.equals(c.getParent().getAnonymizes())))
                .orElse(false);
    }

    private void autoCreateFinanceRecord(TransactionRequest req, Long transactionId) {
        if (req.getSubType() == null) return;
        String name = req.getCounterpartyName();
        String desc = req.getDescription();

        switch (req.getSubType()) {
            case LOAN_RECEIVED -> {
                LoanTakenRequest lt = new LoanTakenRequest();
                lt.setLenderName(name != null ? name : desc);
                lt.setTotalAmount(req.getAmount());
                lt.setCurrency(req.getCurrency());
                lt.setBorrowedDate(req.getTransactionDate());
                lt.setPaymentStartDate(req.getPaymentStartDate());
                lt.setDescription(desc);
                lt.setStatus(RecordStatus.PENDING);
                financeService.createLoanTakenFromTransaction(lt, transactionId);
            }
            case LOAN_GIVEN -> {
                LoanGivenRequest lg = new LoanGivenRequest();
                lg.setDebtorName(name != null ? name : desc);
                lg.setTotalAmount(req.getAmount());
                lg.setCurrency(req.getCurrency());
                lg.setLentDate(req.getTransactionDate());
                lg.setDescription(desc);
                lg.setStatus(RecordStatus.PENDING);
                financeService.createLoanGivenFromTransaction(lg, transactionId);
            }
            case DONATION -> {
                boolean anonymous = isAnonymousCategory(req.getCategoryId());
                DonationRequest dr = new DonationRequest();
                dr.setRecipientName(anonymous ? "Anonymous" : (name != null ? name : (desc != null ? desc : "Donation")));
                dr.setAmount(req.getAmount());
                dr.setCurrency(req.getCurrency());
                dr.setDonationDate(req.getTransactionDate());
                dr.setDescription(desc);
                dr.setAnonymous(anonymous);
                financeService.createDonationFromTransaction(dr, transactionId);
            }
            case INVESTMENT -> {
                if (req.getInvestmentId() != null) {
                    financeService.addFundsToInvestment(req.getInvestmentId(), req.getAmount());
                } else {
                    InvestmentRequest ir = new InvestmentRequest();
                    ir.setName(name != null ? name : desc);
                    ir.setType(req.getInvestmentType() != null ? req.getInvestmentType() : InvestmentType.OTHER);
                    ir.setInvestedAmount(req.getAmount());
                    ir.setCurrency(req.getCurrency());
                    ir.setPurchaseDate(req.getTransactionDate());
                    ir.setDescription(desc);
                    financeService.createInvestmentFromTransaction(ir, transactionId);
                }
            }
            default -> { /* no auto-create for REGULAR / TRANSFER / LOAN_REPAYMENT / BANK_LOAN_PAYMENT */ }
        }
    }

    private void syncFinanceRecordOnUpdate(
            Transaction tx,
            BigDecimal previousAmount,
            TransactionSubType previousSubType,
            Long previousInvestmentId,
            TransactionRequest req
    ) {
        // If sub-type changed (or moved away from an auto-create sub-type), reverse the old
        // record and recreate from scratch — simpler and avoids subtle field-by-field bugs.
        if (previousSubType != req.getSubType()
                || (req.getSubType() == TransactionSubType.INVESTMENT
                    && !java.util.Objects.equals(previousInvestmentId, req.getInvestmentId()))) {
            reverseAutoCreated(tx, previousAmount, previousSubType, previousInvestmentId);
            autoCreateFinanceRecord(req, tx.getId());
            return;
        }

        // Same sub-type — patch in place where possible so amount/description/date stay in sync.
        if (req.getSubType() == null) return;
        switch (req.getSubType()) {
            case LOAN_RECEIVED -> loanTakenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(l -> {
                        l.setTotalAmount(req.getAmount());
                        l.setCurrency(req.getCurrency());
                        l.setBorrowedDate(req.getTransactionDate());
                        if (req.getPaymentStartDate() != null) l.setPaymentStartDate(req.getPaymentStartDate());
                        l.setDescription(req.getDescription());
                        if (req.getCounterpartyName() != null) l.setLenderName(req.getCounterpartyName());
                        loanTakenRepository.save(l);
                    });
            case LOAN_GIVEN -> loanGivenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(l -> {
                        l.setTotalAmount(req.getAmount());
                        l.setCurrency(req.getCurrency());
                        l.setLentDate(req.getTransactionDate());
                        l.setDescription(req.getDescription());
                        if (req.getCounterpartyName() != null) l.setDebtorName(req.getCounterpartyName());
                        loanGivenRepository.save(l);
                    });
            case DONATION -> donationRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(d -> {
                        boolean anonymous = isAnonymousCategory(req.getCategoryId());
                        d.setAmount(req.getAmount());
                        d.setCurrency(req.getCurrency());
                        d.setDonationDate(req.getTransactionDate());
                        d.setDescription(req.getDescription());
                        d.setAnonymous(anonymous);
                        if (anonymous) {
                            d.setRecipientName("Anonymous");
                        } else if (req.getCounterpartyName() != null) {
                            d.setRecipientName(req.getCounterpartyName());
                        }
                        donationRepository.save(d);
                    });
            case INVESTMENT -> {
                if (req.getInvestmentId() != null) {
                    // "Add funds to existing" — diff the amount and apply.
                    BigDecimal diff = req.getAmount().subtract(previousAmount);
                    if (diff.signum() > 0) financeService.addFundsToInvestment(req.getInvestmentId(), diff);
                    else if (diff.signum() < 0) financeService.removeFundsFromInvestment(req.getInvestmentId(), diff.abs());
                } else {
                    investmentRepository.findByOriginatingTransactionId(tx.getId())
                            .ifPresent(i -> {
                                i.setInvestedAmount(req.getAmount());
                                i.setCurrency(req.getCurrency());
                                i.setPurchaseDate(req.getTransactionDate());
                                i.setDescription(req.getDescription());
                                if (req.getInvestmentType() != null) i.setType(req.getInvestmentType());
                                if (req.getCounterpartyName() != null) i.setName(req.getCounterpartyName());
                                investmentRepository.save(i);
                            });
                }
            }
            default -> { /* nothing to sync */ }
        }
    }

    private void reverseFinanceRecordOnDelete(Transaction tx) {
        reverseAutoCreated(tx, tx.getAmount(), tx.getSubType(), tx.getInvestmentId());
    }

    private void reverseAutoCreated(Transaction tx, BigDecimal amount, TransactionSubType subType, Long investmentId) {
        if (subType == null) return;
        switch (subType) {
            case LOAN_RECEIVED -> loanTakenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(loanTakenRepository::delete);
            case LOAN_GIVEN -> loanGivenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(loanGivenRepository::delete);
            case DONATION -> donationRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(donationRepository::delete);
            case INVESTMENT -> {
                if (investmentId != null) {
                    financeService.removeFundsFromInvestment(investmentId, amount);
                } else {
                    investmentRepository.findByOriginatingTransactionId(tx.getId())
                            .ifPresent(investmentRepository::delete);
                }
            }
            default -> { /* no-op */ }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction(Transaction t, TransactionRequest req) {
        t.setType(req.getType());
        t.setAmount(req.getAmount());
        t.setCurrency(req.getCurrency());
        t.setDescription(resolveDescription(req));
        t.setTransactionDate(req.getTransactionDate());
        t.setNote(req.getNote());
        t.setSubType(req.getSubType());
        t.setInvestmentId(req.getInvestmentId());
        // Pure-cash transactions (no card) ALWAYS book the full amount as cash so the
        // cash-balance query can attribute them. Card-linked rows honour whatever the
        // request specified (0 = pure card, > 0 = split payment).
        BigDecimal cash = req.getCashAmount();
        if (req.getCardId() == null) {
            cash = req.getAmount();
        } else if (cash == null) {
            cash = BigDecimal.ZERO;
        }
        t.setCashAmount(cash);
        t.setPlace(emptyToNull(req.getPlace()));
        t.setFromLocation(emptyToNull(req.getFromLocation()));
        t.setToLocation(emptyToNull(req.getToLocation()));
        if (req.getCategoryId() != null) {
            t.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", req.getCategoryId())));
        } else {
            t.setCategory(null);
        }
        if (req.getCardId() != null) {
            t.setCard(cardRepository.findById(req.getCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("Card", req.getCardId())));
        } else {
            t.setCard(null);
        }
        return t;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Description is optional on the wire; if blank we synthesise one from the picked
     * category and any kind-specific extras the user already provided.
     */
    private String resolveDescription(TransactionRequest req) {
        String desc = emptyToNull(req.getDescription());
        if (desc != null) return desc;
        StringBuilder sb = new StringBuilder();
        if (req.getCategoryId() != null) {
            categoryRepository.findById(req.getCategoryId())
                    .ifPresent(cat -> {
                        if (cat.getParent() != null) sb.append(cat.getParent().getName()).append(" — ");
                        sb.append(cat.getName());
                    });
        }
        String place = emptyToNull(req.getPlace());
        String from = emptyToNull(req.getFromLocation());
        String to = emptyToNull(req.getToLocation());
        if (place != null) {
            if (sb.length() > 0) sb.append(" @ ");
            sb.append(place);
        } else if (from != null || to != null) {
            if (sb.length() > 0) sb.append(": ");
            sb.append(from != null ? from : "—").append(" → ").append(to != null ? to : "—");
        }
        return sb.length() > 0 ? sb.toString() : "Transaction";
    }

    private void validateCashAmount(TransactionRequest req) {
        BigDecimal cash = req.getCashAmount();
        if (cash == null) return;
        if (cash.signum() < 0) {
            throw new IllegalArgumentException("Cash amount cannot be negative");
        }
        if (cash.compareTo(req.getAmount()) > 0) {
            throw new IllegalArgumentException("Cash portion cannot exceed total amount");
        }
        if (cash.signum() > 0 && cash.compareTo(req.getAmount()) < 0 && req.getCardId() == null) {
            throw new IllegalArgumentException(
                    "Partial cash payment requires a card for the remaining portion");
        }
    }

    /** The portion of a transaction that hits the linked card (amount minus cashAmount). */
    private BigDecimal cardPortionOf(TransactionRequest req) {
        BigDecimal cash = req.getCashAmount() != null ? req.getCashAmount() : BigDecimal.ZERO;
        BigDecimal portion = req.getAmount().subtract(cash);
        return portion.signum() < 0 ? BigDecimal.ZERO : portion;
    }

    private BigDecimal cardPortionOf(Transaction tx) {
        BigDecimal cash = tx.getCashAmount() != null ? tx.getCashAmount() : BigDecimal.ZERO;
        BigDecimal portion = tx.getAmount().subtract(cash);
        return portion.signum() < 0 ? BigDecimal.ZERO : portion;
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    private void validateCurrencyMatchesCard(Long cardId, Currency txCurrency) {
        if (cardId == null) return;
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (card.getCurrency() != txCurrency) {
            throw new IllegalArgumentException(
                    "Transaction currency (" + txCurrency + ") does not match card currency (" + card.getCurrency() + ")");
        }
    }

    /**
     * For EXPENSE transactions with a card, verify the card has sufficient balance for
     * the card portion of the payment (total minus any cash split).
     * When editing (existing != null), the existing transaction's effect is reversed
     * before the check so a simple edit never triggers a false negative.
     *
     * @param cardId        card the new/edited transaction will hit (may be null → no check)
     * @param cardAmount    the portion that will actually be deducted from the card
     * @param type          income/expense of the new/edited transaction
     * @param existing      the row currently in DB (when editing) so its effect can be reversed
     */
    private void checkCardBalance(Long cardId, BigDecimal cardAmount, TransactionType type, Transaction existing) {
        if (cardId == null || type != TransactionType.EXPENSE) return;
        if (cardAmount.signum() == 0) return; // 100% cash split — card not touched

        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));

        BigDecimal txNet = nullToZero(cardRepository.sumTransactionsByCardId(cardId));
        BigDecimal balance = card.getInitialBalance().add(txNet);

        if (existing != null && existing.getCard() != null && existing.getCard().getId().equals(cardId)) {
            BigDecimal existingCardPortion = cardPortionOf(existing);
            if (existing.getType() == TransactionType.EXPENSE) {
                balance = balance.add(existingCardPortion);
            } else {
                balance = balance.subtract(existingCardPortion);
            }
        }

        if (cardAmount.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                    String.format("Insufficient card balance. Available: %s %s, required: %s %s",
                            balance.setScale(2, RoundingMode.HALF_UP),
                            card.getCurrency(),
                            cardAmount.setScale(2, RoundingMode.HALF_UP),
                            card.getCurrency())
            );
        }
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        return new BigDecimal(value.toString());
    }
}
