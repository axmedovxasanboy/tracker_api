package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.request.MonthCloseRequest;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse;
import uz.tracker.trackerproject.dto.response.MonthClosePreviewResponse.WalletLine;
import uz.tracker.trackerproject.dto.response.MonthCloseResponse;
import uz.tracker.trackerproject.dto.response.MonthSummaryResponse;
import uz.tracker.trackerproject.entity.Card;
import uz.tracker.trackerproject.entity.Category;
import uz.tracker.trackerproject.entity.MonthClose;
import uz.tracker.trackerproject.entity.MonthCloseWallet;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionSubType;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.CardRepository;
import uz.tracker.trackerproject.repository.CashBalanceRepository;
import uz.tracker.trackerproject.repository.CategoryRepository;
import uz.tracker.trackerproject.repository.MonthCloseRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Month-end close + per-wallet reconciliation for the monthly-envelope model.
 *
 * <p>At close the user enters the real end-of-month balance of each wallet. For each wallet
 * we compute its balance as of the month boundary, book a single EVERYDAY_SPENDING adjustment
 * for the difference (so the running balance equals the entered figure — and, because wallet
 * sums are all-time, that entered figure automatically becomes next month's start), and store
 * an immutable UZS snapshot. A closed month can never be reopened, and its transactions are
 * locked (see {@link #assertMonthOpen}).
 */
@Service
@RequiredArgsConstructor
public class MonthCloseService {

    private final MonthCloseRepository monthCloseRepository;
    private final CardRepository cardRepository;
    private final CashBalanceRepository cashBalanceRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final OverviewService overviewService;
    private final SettingsService settingsService;

    // ── Lock guard (called by the transaction-writing services) ───────────────

    /** The most recently closed month, or null if none — all months on/before it are closed. */
    public YearMonth latestClosedMonth() {
        return monthCloseRepository.findTopByOrderByMonthDesc()
                .map(m -> YearMonth.from(m.getMonth()))
                .orElse(null);
    }

    /**
     * Reject any write whose date falls in (or before) a closed month. A closed month is
     * permanent and locked; its transactions can no longer be added, edited or deleted.
     */
    public void assertMonthOpen(LocalDate date) {
        if (date == null) return;
        YearMonth latest = latestClosedMonth();
        if (latest != null && !YearMonth.from(date).isAfter(latest)) {
            throw new IllegalArgumentException(
                    "The month " + YearMonth.from(date) + " is closed and locked — its transactions can no longer be changed.");
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MonthClosePreviewResponse preview(YearMonth month, Currency display) {
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate priorEnd = month.atDay(1).minusDays(1);
        boolean alreadyClosed = monthCloseRepository.existsByMonth(month.atDay(1));

        String blocked = closeBlockedReason(month, alreadyClosed);
        boolean closeable = blocked == null;

        List<WalletLine> lines = new ArrayList<>();
        BigDecimal spendableNow = BigDecimal.ZERO;
        for (Card card : cardRepository.findAll()) {
            BigDecimal computed = nz(card.getInitialBalance())
                    .add(nz(cardRepository.sumTransactionsByCardIdUpTo(card.getId(), monthEnd)));
            lines.add(WalletLine.builder()
                    .walletType("CARD").cardId(card.getId()).label(card.getName())
                    .currency(card.getCurrency()).computedBalance(computed).build());
            spendableNow = spendableNow.add(computed);
        }
        for (Currency c : Currency.reporting()) {
            BigDecimal cashSum = nz(cashBalanceRepository.sumCashlessTransactionsUpTo(c, monthEnd));
            var pot = cashBalanceRepository.findByCurrency(c);
            if (pot.isEmpty() && cashSum.signum() == 0) continue; // no cash activity in this currency
            BigDecimal computed = pot.map(p -> nz(p.getInitialBalance())).orElse(BigDecimal.ZERO).add(cashSum);
            lines.add(WalletLine.builder()
                    .walletType("CASH").cardId(null).label("Cash")
                    .currency(c).computedBalance(computed).build());
            spendableNow = spendableNow.add(computed);
        }

        var income = overviewService.getIncome(month, display);
        // Marks included, so the preview quotes the same bucket figures the plan does. The
        // snapshot's arithmetic is built on the recorded half alone, which `taggedRecorded` names
        // outright — the user sees exactly what will be frozen before committing.
        OverviewService.BucketPaid marks = overviewService.computeBucketMarks(month);
        OverviewService.BucketPaid recorded = overviewService.computePaidThisMonth(month, display, false);
        OverviewService.BucketPaid paid = OverviewService.withBucketMarks(recorded, marks);

        return MonthClosePreviewResponse.builder()
                .month(month.toString())
                .currency(display)
                .alreadyClosed(alreadyClosed)
                .closeable(closeable)
                .blockedReason(blocked)
                .wallets(lines)
                .startBalance(walletBalanceUpTo(priorEnd, display))
                .income(nz(income.getActualIncome()))
                .donation(paid.donation())
                .emergency(paid.emergency())
                .investments(paid.investments())
                .stocks(paid.stocks())
                .savings(paid.savings())
                .taggedTotal(sumBuckets(paid))
                .taggedRecorded(sumBuckets(recorded))
                .markedDonation(marks.donation())
                .markedEmergency(marks.emergency())
                .markedInvestments(marks.investments())
                .markedNotMoved(OverviewService.markedTotal(marks))
                .spendableNow(spendableNow)
                .build();
    }

    // ── Commit ────────────────────────────────────────────────────────────────

    @Transactional
    public MonthCloseResponse close(MonthCloseRequest req) {
        settingsService.assertStableIncomeSet();
        YearMonth month = parseMonth(req.getMonth());
        LocalDate monthFirst = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate priorEnd = monthFirst.minusDays(1);

        String blocked = closeBlockedReason(month, monthCloseRepository.existsByMonth(monthFirst));
        if (blocked != null) throw new IllegalArgumentException(blocked);

        Map<String, BigDecimal> entered = new HashMap<>();
        if (req.getWallets() != null) {
            for (var e : req.getWallets()) {
                entered.put(walletKey(e.getWalletType(), e.getCardId(), e.getCurrency()), e.getEnteredBalance());
            }
        }

        // Snapshot inputs computed BEFORE booking adjustments (income excludes EVERYDAY_SPENDING
        // anyway, but this keeps the figures unambiguous).
        BigDecimal startUzs = walletBalanceUpTo(priorEnd, Currency.UZS);
        BigDecimal incomeUzs = nz(overviewService.getIncome(month, Currency.UZS).getActualIncome());
        // includeMarks = false, and it must stay false: an "already paid" mark moves no tracked
        // money, so folding it into taggedUzs would shrink everydayUzs = totalSpentUzs − taggedUzs
        // by money that never left a wallet — permanently, since a closed month can't be reopened.
        // getMonthSummary adds the marks back on TOP of these columns when it reads the month
        // again, so nothing the user is looking at changes; only the arithmetic stays honest.
        OverviewService.BucketPaid paid = overviewService.computePaidThisMonth(month, Currency.UZS, false);

        MonthClose close = new MonthClose();
        close.setMonth(monthFirst);
        BigDecimal leftoverUzs = BigDecimal.ZERO;

        for (Card card : cardRepository.findAll()) {
            BigDecimal computed = nz(card.getInitialBalance())
                    .add(nz(cardRepository.sumTransactionsByCardIdUpTo(card.getId(), monthEnd)));
            BigDecimal enteredBal = entered.getOrDefault(walletKey("CARD", card.getId(), card.getCurrency()), computed);
            BigDecimal delta = computed.subtract(enteredBal);
            Long txId = bookAdjustment(card, card.getCurrency(), delta, monthEnd);
            close.addWallet(walletRow("CARD", card.getId(), card.getCurrency(), computed, enteredBal, delta, txId));
            leftoverUzs = leftoverUzs.add(enteredBal);
        }
        for (Currency c : Currency.reporting()) {
            BigDecimal cashSum = nz(cashBalanceRepository.sumCashlessTransactionsUpTo(c, monthEnd));
            var pot = cashBalanceRepository.findByCurrency(c);
            boolean requested = entered.containsKey(walletKey("CASH", null, c));
            if (pot.isEmpty() && cashSum.signum() == 0 && !requested) continue;
            BigDecimal computed = pot.map(p -> nz(p.getInitialBalance())).orElse(BigDecimal.ZERO).add(cashSum);
            BigDecimal enteredBal = entered.getOrDefault(walletKey("CASH", null, c), computed);
            BigDecimal delta = computed.subtract(enteredBal);
            Long txId = bookAdjustment(null, c, delta, monthEnd);
            close.addWallet(walletRow("CASH", null, c, computed, enteredBal, delta, txId));
            leftoverUzs = leftoverUzs.add(enteredBal);
        }

        BigDecimal taggedUzs = sumBuckets(paid);
        BigDecimal totalSpentUzs = startUzs.add(incomeUzs).subtract(leftoverUzs);
        BigDecimal everydayUzs = totalSpentUzs.subtract(taggedUzs);

        close.setStartBalanceUzs(startUzs);
        close.setIncomeUzs(incomeUzs);
        close.setDonationUzs(paid.donation());
        close.setEmergencyUzs(paid.emergency());
        close.setInvestmentsUzs(paid.investments());
        close.setStocksUzs(paid.stocks());
        close.setSavingsUzs(paid.savings());
        close.setEverydaySpendUzs(everydayUzs);
        close.setTotalSpentUzs(totalSpentUzs);
        close.setLeftoverUzs(leftoverUzs);

        return MonthCloseResponse.from(monthCloseRepository.save(close));
    }

    @Transactional(readOnly = true)
    public List<MonthCloseResponse> listClosed() {
        return monthCloseRepository.findAllByOrderByMonthDesc().stream()
                .map(MonthCloseResponse::from).toList();
    }

    // ── Monthly-envelope summary (the "earned / spent / left" view) ────────────

    /**
     * The envelope view for one month. Open and closed months differ in ONE thing — where the
     * recorded bucket totals come from (live sums vs the frozen snapshot) — and in nothing else.
     * Marks are folded in on top either way, so closing a month never changes a bucket figure the
     * user is reading, and the Plan, Home, the bucket tabs and this page cannot disagree.
     *
     * <p>Marks are safe to add to a frozen snapshot because they are frozen too: both
     * {@code FinanceService.markPaid} and {@code FinanceService.deleteMark} go through
     * {@link #assertMonthOpen}, so a closed month's marks can be neither created nor removed. No
     * stored MonthClose row is read differently, let alone rewritten — the snapshot keeps its
     * recorded-only meaning and is reported as {@code taggedRecorded}, which is what
     * {@code everydaySpend = totalSpent − taggedRecorded} balances against.
     */
    @Transactional(readOnly = true)
    public MonthSummaryResponse getMonthSummary(YearMonth month, Currency display) {
        var closeOpt = monthCloseRepository.findByMonth(month.atDay(1));
        OverviewService.BucketPaid marks = overviewService.computeBucketMarks(month);

        // Recorded-only bucket totals — real wallet movement. Frozen once the month is closed;
        // recomputed live while it is open. The rest of this method treats the two identically.
        OverviewService.BucketPaid recorded;
        BigDecimal startBalance, income, everydaySpend, totalSpent, leftover;
        if (closeOpt.isPresent()) {
            MonthClose m = closeOpt.get();
            recorded = new OverviewService.BucketPaid(
                    nz(m.getDonationUzs()), nz(m.getEmergencyUzs()), nz(m.getInvestmentsUzs()),
                    nz(m.getStocksUzs()), nz(m.getSavingsUzs()));
            startBalance = nz(m.getStartBalanceUzs());
            income = nz(m.getIncomeUzs());
            everydaySpend = nz(m.getEverydaySpendUzs());
            totalSpent = nz(m.getTotalSpentUzs());
            leftover = nz(m.getLeftoverUzs());
        } else {
            recorded = overviewService.computePaidThisMonth(month, display, false);
            startBalance = walletBalanceUpTo(month.atDay(1).minusDays(1), display);
            income = nz(overviewService.getIncome(month, display).getActualIncome());
            // Unknown until the user enters their real end-of-month balances at close.
            everydaySpend = null;
            totalSpent = null;
            leftover = null;
        }

        OverviewService.BucketPaid shown = OverviewService.withBucketMarks(recorded, marks);

        return MonthSummaryResponse.builder()
                .month(month.toString()).currency(display).closed(closeOpt.isPresent())
                .startBalance(startBalance)
                .income(income)
                .donation(shown.donation()).emergency(shown.emergency()).investments(shown.investments())
                .stocks(shown.stocks()).savings(shown.savings())
                .taggedTotal(sumBuckets(shown))
                .taggedRecorded(sumBuckets(recorded))
                .markedDonation(marks.donation())
                .markedEmergency(marks.emergency())
                .markedInvestments(marks.investments())
                .markedNotMoved(OverviewService.markedTotal(marks))
                .everydaySpend(everydaySpend)
                .totalSpent(totalSpent)
                .leftover(leftover)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Σ of a month's five bucket figures. The preview, the close and the summary all quote this
     * sum; writing it out three times is how one of them once ended up dropping a bucket.
     */
    private static BigDecimal sumBuckets(OverviewService.BucketPaid p) {
        return p.donation().add(p.emergency()).add(p.investments()).add(p.stocks()).add(p.savings());
    }

    /** Returns the reason this month cannot be closed, or null when it can. */
    private String closeBlockedReason(YearMonth month, boolean alreadyClosed) {
        if (alreadyClosed) return "The month " + month + " is already closed.";
        if (month.isAfter(YearMonth.now())) return "You can only close the current or a past month.";
        YearMonth latest = latestClosedMonth();
        if (latest != null && !month.equals(latest.plusMonths(1))) {
            return "Close months in order — the next month to close is " + latest.plusMonths(1) + ".";
        }
        return null;
    }

    /** Total of every wallet's balance as of {@code end}. */
    private BigDecimal walletBalanceUpTo(LocalDate end, Currency target) {
        BigDecimal total = BigDecimal.ZERO;
        for (Card card : cardRepository.findAll()) {
            BigDecimal bal = nz(card.getInitialBalance())
                    .add(nz(cardRepository.sumTransactionsByCardIdUpTo(card.getId(), end)));
            total = total.add(bal);
        }
        for (Currency c : Currency.reporting()) {
            BigDecimal cashSum = nz(cashBalanceRepository.sumCashlessTransactionsUpTo(c, end));
            BigDecimal init = cashBalanceRepository.findByCurrency(c)
                    .map(p -> nz(p.getInitialBalance())).orElse(BigDecimal.ZERO);
            BigDecimal bal = init.add(cashSum);
            if (bal.signum() == 0) continue;
            total = total.add(bal);
        }
        return total;
    }

    /**
     * Book the EVERYDAY_SPENDING reconciliation transaction for one wallet. delta = computed −
     * entered: positive ⇒ untracked spend (EXPENSE), negative ⇒ surplus / unaccounted (INCOME).
     * Built directly (not via TransactionService.create) so the card balance check doesn't reject
     * it — it IS the spend that drained the wallet. Returns the new tx id, or null when delta == 0.
     */
    private Long bookAdjustment(Card card, Currency currency, BigDecimal delta, LocalDate date) {
        if (delta == null || delta.signum() == 0) return null;
        BigDecimal amount = delta.abs();
        Transaction tx = new Transaction();
        tx.setCurrency(currency);
        tx.setSubType(TransactionSubType.EVERYDAY_SPENDING);
        tx.setTransactionDate(date);
        tx.setAmount(amount);
        if (delta.signum() > 0) {
            tx.setType(TransactionType.EXPENSE);
            tx.setDescription("Everyday spending (month-end reconciliation)");
            // Auto-pick the Everyday category so it shows in the category breakdown.
            List<Category> cats = categoryRepository.findByApplicableSubTypeAndParentIsNull(
                    TransactionSubType.EVERYDAY_SPENDING);
            if (cats.size() == 1) tx.setCategory(cats.get(0));
        } else {
            tx.setType(TransactionType.INCOME);
            tx.setDescription("Month-end surplus (reconciliation)");
        }
        if (card != null) {
            tx.setCard(card);
            tx.setCashAmount(BigDecimal.ZERO);
        } else {
            tx.setCard(null);
            tx.setCashAmount(amount); // cash pot: full amount as cash (buildTransaction convention)
        }
        return transactionRepository.save(tx).getId();
    }

    private MonthCloseWallet walletRow(String type, Long cardId, Currency currency,
                                       BigDecimal computed, BigDecimal entered, BigDecimal delta, Long txId) {
        MonthCloseWallet w = new MonthCloseWallet();
        w.setWalletType(type);
        w.setCardId(cardId);
        w.setCurrency(currency);
        w.setComputedBalance(computed);
        w.setEnteredBalance(entered);
        w.setEverydaySpend(delta);
        w.setAdjustmentTxId(txId);
        return w;
    }

    private static String walletKey(String type, Long cardId, Currency currency) {
        String t = type == null ? "" : type.trim().toUpperCase();
        return "CARD".equals(t) ? "CARD:" + cardId : "CASH:" + currency;
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("month is required (YYYY-MM)");
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be in YYYY-MM format (got: " + month + ")");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
