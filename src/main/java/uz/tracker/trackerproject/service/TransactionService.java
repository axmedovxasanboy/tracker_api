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
import uz.tracker.trackerproject.entity.Investment;
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
    private final MonthCloseService monthCloseService;
    private final SettingsService settingsService;
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
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(request.getTransactionDate());
        bookByHoldingKind(request);
        validateCashAmount(request);
        validateCurrencyMatchesCard(request.getCardId(), request.getCurrency());
        checkCardBalance(request.getCardId(), cardPortionOf(request), request.getType(), null);
        Transaction transaction = buildTransaction(new Transaction(), request);
        Transaction saved = transactionRepository.save(transaction);
        autoCreateFinanceRecord(request, saved.getId());
        return TransactionResponse.from(saved);
    }

    @Transactional
    public TransactionResponse update(Long id, TransactionRequest request) {
        Transaction existing = findOrThrow(id);
        // Lock both the existing month (can't edit a closed month's row) and the target month
        // (can't move a row into a closed month).
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(existing.getTransactionDate());
        monthCloseService.assertMonthOpen(request.getTransactionDate());
        bookByHoldingKind(request);
        validateCashAmount(request);
        validateCurrencyMatchesCard(request.getCardId(), request.getCurrency());
        checkCardBalance(request.getCardId(), cardPortionOf(request), request.getType(), existing);

        BigDecimal previousAmount = existing.getAmount();
        TransactionSubType previousSubType = existing.getSubType();
        Long previousInvestmentId = existing.getInvestmentId();
        Long previousLoanGivenId = existing.getLoanGivenId();

        Transaction transaction = buildTransaction(existing, request);
        Transaction saved = transactionRepository.save(transaction);

        syncFinanceRecordOnUpdate(saved, previousAmount, previousSubType, previousInvestmentId,
                previousLoanGivenId, request);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        monthCloseService.assertMonthOpen(tx.getTransactionDate());
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

    @Transactional
    public List<TransactionResponse> transferBalance(BalanceTransferRequest request) {
        settingsService.assertStableIncomeSet();
        monthCloseService.assertMonthOpen(request.getTransactionDate());
        // A null card id on either side means the CASH pot. Cash is modelled as a
        // transaction with no card, so a cash↔card transfer is the same expense/income
        // pair as card↔card — one of the two rows simply has card == null.
        Long fromId = request.getFromCardId();
        Long toId = request.getToCardId();
        if (fromId == null && toId == null) {
            throw new IllegalArgumentException("A transfer needs a card on at least one side.");
        }
        if (fromId != null && fromId.equals(toId)) {
            throw new IllegalArgumentException("Source and destination cards must be different");
        }

        Card fromCard = fromId == null ? null : cardRepository.findById(fromId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", fromId));
        Card toCard = toId == null ? null : cardRepository.findById(toId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", toId));

        if (fromCard != null && toCard != null && fromCard.getCurrency() != toCard.getCurrency()) {
            throw new IllegalArgumentException(
                    "Cannot transfer between cards with different currencies (" +
                            fromCard.getCurrency() + " → " + toCard.getCurrency() +
                            "). Currency conversion is not supported yet.");
        }

        // Cash pots are per-currency and nothing converts, so the cash side always takes
        // the card side's currency — a transfer can never cross currencies.
        Currency currency = fromCard != null ? fromCard.getCurrency() : toCard.getCurrency();

        if (fromCard != null) {
            checkCardBalance(fromId, request.getAmount(), TransactionType.EXPENSE, null);
        } else {
            checkCashBalance(currency, request.getAmount());
        }

        String desc = (request.getDescription() != null && !request.getDescription().isBlank())
                ? request.getDescription() : "Balance transfer";

        Transaction expense = new Transaction();
        expense.setType(TransactionType.EXPENSE);
        expense.setAmount(request.getAmount());
        expense.setCurrency(currency);
        expense.setCard(fromCard);
        // Cardless rows must carry cashAmount = amount (see CashBalanceRepository).
        if (fromCard == null) expense.setCashAmount(request.getAmount());
        expense.setDescription(desc);
        expense.setTransactionDate(request.getTransactionDate());
        expense.setSubType(TransactionSubType.TRANSFER_OUT);
        expense.setNote("Transfer to " + walletLabel(toCard));

        Transaction income = new Transaction();
        income.setType(TransactionType.INCOME);
        income.setAmount(request.getAmount());
        income.setCurrency(currency);
        income.setCard(toCard);
        if (toCard == null) income.setCashAmount(request.getAmount());
        income.setDescription(desc);
        income.setTransactionDate(request.getTransactionDate());
        income.setSubType(TransactionSubType.TRANSFER_IN);
        income.setNote("Transfer from " + walletLabel(fromCard));

        Transaction savedExpense = transactionRepository.save(expense);
        Transaction savedIncome = transactionRepository.save(income);

        // Link the two with a shared pair id (the expense's persisted id) so deletes can cascade.
        savedExpense.setTransferPairId(savedExpense.getId());
        savedIncome.setTransferPairId(savedExpense.getId());
        transactionRepository.save(savedExpense);
        transactionRepository.save(savedIncome);

        return List.of(TransactionResponse.from(savedExpense), TransactionResponse.from(savedIncome));
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
                .spendableBalance(available)
                .netWorth(computeNetWorth(currency))
                .build();
    }

    /**
     * Net worth = spendable wallet money + the current value of every investment / savings
     * goal. Unlike spendableBalance (wallets only), this is the full "everything I own" figure.
     */
    private BigDecimal computeNetWorth(Currency display) {
        BigDecimal total = computeAvailableBalance(display);
        for (Investment i : investmentRepository.findAll()) {
            // Defensive: investments are created in UZS, but adding a foreign one raw would
            // corrupt net worth silently (nothing converts). Null currency = legacy row.
            if (i.getCurrency() != null && i.getCurrency() != display) continue;
            BigDecimal value = i.getCurrentValue() != null ? i.getCurrentValue() : i.getInvestedAmount();
            if (value != null) total = total.add(value);
        }
        return total;
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
                  AND (sub_type IS NULL OR sub_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT', 'INVESTMENT_WITHDRAWAL'))
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
                  AND (t.sub_type IS NULL OR t.sub_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT', 'INVESTMENT_WITHDRAWAL'))
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
                lt.setLenderName(firstNonBlank(name, desc, "Lender"));
                lt.setTotalAmount(req.getAmount());
                lt.setCurrency(req.getCurrency());
                lt.setBorrowedDate(req.getTransactionDate());
                lt.setPaymentStartDate(req.getPaymentStartDate());
                lt.setDescription(desc);
                lt.setStatus(RecordStatus.PENDING);
                financeService.createLoanTakenFromTransaction(lt, transactionId);
            }
            case LOAN_GIVEN -> {
                // "He asked again" — add to the borrower's existing loan rather than
                // creating a duplicate record for the same person.
                if (req.getLoanGivenId() != null) {
                    financeService.addToLoanGiven(req.getLoanGivenId(), req.getAmount());
                    break;
                }
                LoanGivenRequest lg = new LoanGivenRequest();
                lg.setDebtorName(firstNonBlank(name, desc, "Borrower"));
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
                    // Investment.name is NOT NULL — never let a blank description reach it,
                    // or the constraint violation rolls the whole transaction back.
                    ir.setName(firstNonBlank(name, desc, "Investment"));
                    ir.setType(req.getInvestmentType() != null ? req.getInvestmentType() : InvestmentType.OTHER);
                    ir.setInvestedAmount(req.getAmount());
                    ir.setCurrency(req.getCurrency());
                    ir.setPurchaseDate(req.getTransactionDate());
                    ir.setDescription(desc);
                    financeService.createInvestmentFromTransaction(ir, transactionId);
                }
            }
            case INVESTMENT_WITHDRAWAL -> {
                // Money taken out of a holding: the holding goes down by it.
                if (req.getInvestmentId() != null) {
                    financeService.applyWithdrawal(req.getInvestmentId(), req.getAmount());
                }
            }
            case EMERGENCY_CONTRIBUTION -> {
                // Only "top up an existing emergency fund" applies here. A bare
                // EMERGENCY_CONTRIBUTION with no investmentId (the Emergencies-tab shape) has
                // no linked record to create — the tx alone feeds the Emergency bucket.
                if (req.getInvestmentId() != null) {
                    financeService.addFundsToInvestment(req.getInvestmentId(), req.getAmount());
                }
            }
            default -> { /* no auto-create for REGULAR / TRANSFER / LOAN_REPAYMENT / BANK_LOAN_PAYMENT */ }
        }
    }

    /** First non-blank of the candidates; the last one is the guaranteed fallback. */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return "—";
    }

    private void syncFinanceRecordOnUpdate(
            Transaction tx,
            BigDecimal previousAmount,
            TransactionSubType previousSubType,
            Long previousInvestmentId,
            Long previousLoanGivenId,
            TransactionRequest req
    ) {
        // A top-up re-filed between INVESTMENT and EMERGENCY_CONTRIBUTION against the SAME holding is
        // a relabel, not a different record: the label follows the holding's emergency-fund flag
        // (see bookByHoldingKind), so it flips whenever that flag has changed since the row was
        // written. Reversing and recreating would delete a holding whose own funding row this is.
        boolean relabelledTopUp = fundsAHolding(previousSubType) && fundsAHolding(req.getSubType())
                && req.getInvestmentId() != null
                && java.util.Objects.equals(previousInvestmentId, req.getInvestmentId());

        // If sub-type changed (or moved away from an auto-create sub-type), reverse the old
        // record and recreate from scratch — simpler and avoids subtle field-by-field bugs.
        if ((previousSubType != req.getSubType() && !relabelledTopUp)
                || ((req.getSubType() == TransactionSubType.INVESTMENT
                        || req.getSubType() == TransactionSubType.EMERGENCY_CONTRIBUTION
                        || req.getSubType() == TransactionSubType.INVESTMENT_WITHDRAWAL)
                    && !java.util.Objects.equals(previousInvestmentId, req.getInvestmentId()))
                || (req.getSubType() == TransactionSubType.LOAN_GIVEN
                    && !java.util.Objects.equals(previousLoanGivenId, req.getLoanGivenId()))) {
            reverseAutoCreated(tx, previousAmount, previousSubType, previousInvestmentId, previousLoanGivenId);
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
                        if (req.getCounterpartyName() != null) {
                            l.setLenderName(req.getCounterpartyName());
                            financeService.relinkLender(l);   // the name decides the person
                        }
                        loanTakenRepository.save(l);
                    });
            case LOAN_GIVEN -> {
                if (req.getLoanGivenId() != null) {
                    // Top-up of an existing loan: diff the amount, same as investments.
                    BigDecimal diff = req.getAmount().subtract(previousAmount);
                    if (diff.signum() > 0) financeService.addToLoanGiven(req.getLoanGivenId(), diff);
                    else if (diff.signum() < 0) financeService.removeFromLoanGiven(req.getLoanGivenId(), diff.abs());
                    break;
                }
                loanGivenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(l -> {
                        l.setTotalAmount(req.getAmount());
                        l.setCurrency(req.getCurrency());
                        l.setLentDate(req.getTransactionDate());
                        l.setDescription(req.getDescription());
                        if (req.getCounterpartyName() != null) {
                            l.setDebtorName(req.getCounterpartyName());
                            financeService.relinkBorrower(l);
                        }
                        loanGivenRepository.save(l);
                    });
            }
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
            case INVESTMENT_WITHDRAWAL -> {
                // Edited withdrawal: only the difference moves the holding.
                if (req.getInvestmentId() != null) {
                    BigDecimal diff = req.getAmount().subtract(previousAmount);
                    if (diff.signum() > 0) financeService.applyWithdrawal(req.getInvestmentId(), diff);
                    else if (diff.signum() < 0) financeService.reverseWithdrawal(req.getInvestmentId(), diff.abs());
                }
            }
            case INVESTMENT, EMERGENCY_CONTRIBUTION -> {
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
        reverseAutoCreated(tx, tx.getAmount(), tx.getSubType(), tx.getInvestmentId(), tx.getLoanGivenId());
    }

    private void reverseAutoCreated(Transaction tx, BigDecimal amount, TransactionSubType subType,
                                    Long investmentId, Long loanGivenId) {
        if (subType == null) return;
        switch (subType) {
            case LOAN_RECEIVED -> loanTakenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(loanTakenRepository::delete);
            // Same shape as INVESTMENT below: a tx that ORIGINATED the loan deletes the
            // record; a top-up tx just backs its amount out of the borrower's total.
            case LOAN_GIVEN -> loanGivenRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresentOrElse(loanGivenRepository::delete,
                            () -> {
                                if (loanGivenId != null) {
                                    financeService.removeFromLoanGiven(loanGivenId, amount);
                                }
                            });
            case DONATION -> donationRepository.findByOriginatingTransactionId(tx.getId())
                    .ifPresent(donationRepository::delete);
            // A withdrawal deleted: the money goes back into the holding.
            case INVESTMENT_WITHDRAWAL -> {
                if (investmentId != null) financeService.reverseWithdrawal(investmentId, amount);
            }
            case INVESTMENT, EMERGENCY_CONTRIBUTION -> {
                // The tx that ORIGINATED an investment deletes the record itself; a
                // contribution tx (investmentId set) just backs its amount out of the fund.
                // Originating txs may carry investmentId too, so that check goes first —
                // otherwise deleting a fund-creating tx would zero the fund's total but
                // leave the record behind.
                investmentRepository.findByOriginatingTransactionId(tx.getId()).ifPresentOrElse(
                        inv -> {
                            assertNoOtherContributions(inv, tx.getId());
                            investmentRepository.delete(inv);
                        },
                        () -> {
                            if (investmentId != null) {
                                financeService.removeFundsFromInvestment(investmentId, amount);
                            }
                        });
            }
            default -> { /* no-op */ }
        }
    }

    /**
     * Removing the transaction that created an investment removes the investment too — but its
     * own contribution transactions would then point at a record that no longer exists (and an
     * orphaned EMERGENCY_CONTRIBUTION keeps crediting the Emergency bucket forever). Refuse
     * instead, so the user clears the contributions deliberately. This is the same rule
     * {@code FinanceService.deleteInvestment} applies from the other direction.
     */
    private void assertNoOtherContributions(Investment inv, Long excludedTxId) {
        List<Transaction> contributions =
                transactionRepository.findByInvestmentIdOrderByTransactionDateDesc(inv.getId()).stream()
                        .filter(t -> !java.util.Objects.equals(t.getId(), excludedTxId))
                        .toList();
        if (!contributions.isEmpty()) {
            throw new IllegalArgumentException(
                    "This transaction created \"" + inv.getName() + "\", which has "
                    + contributions.size() + " contribution transaction(s) linked to it. "
                    + "Delete those first.");
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
        t.setLoanGivenId(req.getLoanGivenId());
        // The allocation bucket this payment funds is recorded HERE, once, rather than re-derived
        // on every read from the target holding's savingsGoal flag: that flag outlives the month
        // the money moved in, so flipping it used to re-bucket months that were already closed
        // (see AllocationBucket). An edit re-decides it, which is correct — editing a transaction
        // is gated on both its old and its new month being open, so a genuine mis-categorisation
        // stays correctable while a closed month cannot move.
        t.setAllocationBucket(AllocationBucket.forSubType(req.getSubType(), fundsASavingsGoal(req)));
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

    /**
     * File money put into an existing holding by what the holding IS: an emergency fund takes an
     * EMERGENCY_CONTRIBUTION, any other holding an INVESTMENT — the rule the Investments tab and the
     * Plan's Record button already follow (FinanceService.contributeToInvestment). This page and the
     * bot let an emergency fund be picked under "Investment", and the row used to be saved as sent:
     * the fund grew, the Investments bucket was credited and the Emergency bucket was not, while the
     * allocation preview had promised Emergency. Runs before anything reads the sub-type, so the
     * bucket stamp, the finance-record sync and the response all see the booked one.
     */
    private void bookByHoldingKind(TransactionRequest req) {
        if (req.getInvestmentId() == null || !fundsAHolding(req.getSubType())) return;
        investmentRepository.findById(req.getInvestmentId())
                .ifPresent(holding -> req.setSubType(AllocationBucket.forHolding(req.getSubType(), holding)));
    }

    /** The two sub-types that can put money into an existing holding. */
    private static boolean fundsAHolding(TransactionSubType subType) {
        return subType == TransactionSubType.INVESTMENT
                || subType == TransactionSubType.EMERGENCY_CONTRIBUTION;
    }

    /**
     * Does this request's INVESTMENT row put money into a savings goal rather than a plain
     * investment? Only a top-up carries an investmentId; a row that CREATES the holding cannot,
     * because the holding is created from the transaction afterwards — and a holding created that
     * way is never a goal (see {@code autoCreateFinanceRecord}), so "no id" means "not a goal".
     */
    private boolean fundsASavingsGoal(TransactionRequest req) {
        if (req.getSubType() != TransactionSubType.INVESTMENT || req.getInvestmentId() == null) {
            return false;
        }
        return investmentRepository.findById(req.getInvestmentId())
                .map(i -> Boolean.TRUE.equals(i.getSavingsGoal()))
                .orElse(false);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Description is optional on the wire. Left blank, a transaction is named after WHO it was
     * with — the borrower of a loan given, the lender of a loan received, a donation's recipient,
     * the holding an investment went into — and only failing that after its category.
     *
     * The name comes first because it is the one thing that tells two rows of the same category
     * apart. The category is already on every row as its badge, so a list of loans named after
     * their category was a column of identical "Loan Given" lines with the borrower nowhere on
     * screen — the counterparty name reached the finance record and nothing else, since it is not
     * stored on the transaction. Turning a category's description requirement off is what
     * exposed it: until then the borrower was typed a second time into the description.
     *
     * An anonymous donation keeps the category name: "Anonymous" as a title says less than
     * "Donation — Anonymous", and the category is what declares the anonymity.
     */
    private String resolveDescription(TransactionRequest req) {
        String desc = emptyToNull(req.getDescription());
        if (desc != null) return desc;
        String counterparty = emptyToNull(req.getCounterpartyName());
        if (counterparty != null && !"Anonymous".equalsIgnoreCase(counterparty)
                && !isAnonymousCategory(req.getCategoryId())) {
            return counterparty;
        }
        if (req.getCategoryId() != null) {
            String named = categoryRepository.findById(req.getCategoryId())
                    .map(cat -> cat.getParent() != null
                            ? cat.getParent().getName() + " — " + cat.getName()
                            : cat.getName())
                    .orElse(null);
            if (named != null) return named;
        }
        return "Transaction";
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
    /** Human label for either side of a transfer; null is the cash pot. */
    private static String walletLabel(Card card) {
        return card == null ? "Cash" : card.getName() + " (•••• " + card.getLastFourDigits() + ")";
    }

    /**
     * Guards the cash side of a transfer. Ordinary cash expenses are deliberately not
     * balance-checked, but a transfer OUT of cash credits a card, so letting it overdraw
     * would fabricate money rather than merely record an overspend.
     */
    private void checkCashBalance(Currency currency, BigDecimal amount) {
        BigDecimal initial = cashBalanceRepository.findByCurrency(currency)
                .map(cb -> nullToZero(cb.getInitialBalance()))
                .orElse(BigDecimal.ZERO);
        BigDecimal balance = initial.add(nullToZero(cashBalanceRepository.sumCashlessTransactions(currency)));
        if (amount.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                    String.format("Insufficient cash balance. Available: %s %s, required: %s %s",
                            balance.setScale(2, RoundingMode.HALF_UP), currency,
                            amount.setScale(2, RoundingMode.HALF_UP), currency));
        }
    }

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
