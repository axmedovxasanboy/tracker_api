package uz.tracker.trackerproject.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.BucketLedger;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.MonthBreakdown;
import uz.tracker.trackerproject.dto.response.AllocationLedgerResponse.MonthBucketLine;
import uz.tracker.trackerproject.dto.response.OverviewIncomeResponse;
import uz.tracker.trackerproject.dto.response.OverviewTierResponse;
import uz.tracker.trackerproject.dto.response.TierAllocation;
import uz.tracker.trackerproject.dto.response.TierAllocation.ActionItem;
import uz.tracker.trackerproject.dto.response.TierAllocation.AllocationLine;
import uz.tracker.trackerproject.entity.BankLoan;
import uz.tracker.trackerproject.entity.Debt;
import uz.tracker.trackerproject.entity.Donation;
import uz.tracker.trackerproject.entity.Investment;
import uz.tracker.trackerproject.entity.Transaction;
import uz.tracker.trackerproject.entity.LevelAllocationRule;
import uz.tracker.trackerproject.entity.LevelConfig;
import uz.tracker.trackerproject.entity.LoanTaken;
import uz.tracker.trackerproject.entity.MarkPaid;
import uz.tracker.trackerproject.entity.MonthlyPayment;
import uz.tracker.trackerproject.entity.Settings;
import uz.tracker.trackerproject.enums.Currency;
import uz.tracker.trackerproject.enums.TransactionType;
import uz.tracker.trackerproject.repository.BankLoanRepository;
import uz.tracker.trackerproject.repository.DebtRepository;
import uz.tracker.trackerproject.repository.DonationRepository;
import uz.tracker.trackerproject.repository.InvestmentRepository;
import uz.tracker.trackerproject.repository.LoanTakenRepository;
import uz.tracker.trackerproject.repository.LevelAllocationRuleRepository;
import uz.tracker.trackerproject.repository.LevelConfigRepository;
import uz.tracker.trackerproject.repository.MarkPaidRepository;
import uz.tracker.trackerproject.repository.MonthlyPaymentRepository;
import uz.tracker.trackerproject.repository.TransactionRepository;
import uz.tracker.trackerproject.dto.request.LevelAllocationRuleRequest;
import uz.tracker.trackerproject.dto.request.LevelConfigRequest;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.LevelView;
import uz.tracker.trackerproject.dto.response.AllocationRulesViewResponse.SubLevelView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OverviewService {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal DEBT_RATIO_THRESHOLD = new BigDecimal("0.70");
    private static final BigDecimal[] LEVEL_BREAKPOINTS_UZS = {
            new BigDecimal("15000000"),  // < this → level 1
            new BigDecimal("30000000"),  // < this → level 2
            new BigDecimal("45000000"),  // < this → level 3
            new BigDecimal("60000000"),  // < this → level 4
            new BigDecimal("75000000"),  // < this → level 5
            new BigDecimal("90000000"),  // < this → level 6
    };

    /** Threshold for "tight vs comfortable" within Level 1.2 / 1.3 (5M UZS). */
    private static final BigDecimal FIVE_MILLION_UZS = new BigDecimal("5000000");
    /** Recommended personal-loan repayment portion ("at least 34% per month"). */
    private static final BigDecimal PERSONAL_LOAN_PAYDOWN_RATE = new BigDecimal("0.34");
    /**
     * Fraction of a bank installment's average monthly amount that must be paid for the
     * action to count as "met" and unlock allocation recording (90% of the average).
     */
    private static final BigDecimal BANK_UNLOCK_RATE = new BigDecimal("0.90");

    private final TransactionRepository transactionRepository;
    private final MonthlyPaymentRepository monthlyPaymentRepository;
    private final BankLoanRepository bankLoanRepository;
    private final LoanTakenRepository loanTakenRepository;
    private final DebtRepository debtRepository;
    private final DonationRepository donationRepository;
    private final InvestmentRepository investmentRepository;
    private final LevelAllocationRuleRepository ruleRepository;
    private final LevelConfigRepository levelConfigRepository;
    private final MarkPaidRepository markPaidRepository;
    private final SettingsService settingsService;

    @Transactional(readOnly = true)
    public OverviewIncomeResponse getIncome(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        // Sum this month's INCOME transactions. Iterating the enum keeps this working
        // unchanged if more currencies are ever reintroduced.
        BigDecimal actual = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumByTypeCurrencyDateRange(
                    TransactionType.INCOME, c, start, end);
            if (sum == null || sum.signum() == 0) continue;
            actual = actual.add(sum);
        }

        Settings s = settingsService.getOrCreate();
        BigDecimal stable = null;
        if (s.getMonthlyStableIncome() != null) {
            stable = s.getMonthlyStableIncome();
        }

        return OverviewIncomeResponse.builder()
                .month(month.toString())
                .currency(displayCurrency)
                .actualIncome(actual)
                .stableIncome(stable)
                .build();
    }

    // ── Tier ──────────────────────────────────────────────────────────────────

    /**
     * Compute the user's financial tier for a given month. Level/sub-level reflect
     * the CURRENT snapshot (debts, subscriptions, income) — we don't store historical
     * snapshots — but the per-bucket "paid this month" math IS scoped to the requested
     * month. So switching months shows the current tier with that month's payment
     * progress.
     */
    @Transactional(readOnly = true)
    public OverviewTierResponse getTier(YearMonth month, Currency displayCurrency) {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        BigDecimal incomeUzs = missingIncome ? BigDecimal.ZERO
                : s.getMonthlyStableIncome();

        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        BigDecimal leftMoneyUzs = incomeUzs.subtract(mandatoryUzs);

        LocalDate today = LocalDate.now();
        // The owner's model: the only "monthly loan installment" is a BANK loan. Money borrowed
        // from a person (LoanTaken) and money owed (Debt) are BOTH debt → paid at 34% of original.
        // Gated by payment-start so a not-yet-started obligation doesn't move this month's tier.
        BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(today);
        BigDecimal loanTaken34Uzs = sumLoanTaken34Uzs(month);
        BigDecimal debtRows34Uzs = sumDebtRows34Uzs(month);
        BigDecimal plannedSetAsideUzs = sumLoanTakenPlannedUzs(month);
        BigDecimal loanInstallmentsUzs = bankUzs;
        BigDecimal debt34Uzs = loanTaken34Uzs.add(debtRows34Uzs);
        BigDecimal debtPaymentsUzs = loanInstallmentsUzs.add(debt34Uzs);

        BigDecimal debtRatio = incomeUzs.signum() > 0
                ? debtPaymentsUzs.divide(incomeUzs, MC)
                : null;

        Integer level = missingIncome ? null : computeLevel(leftMoneyUzs);
        String subLevel = computeSubLevel(level, debtPaymentsUzs, debtRatio);
        String levelLabel = computeLevelLabel(level, subLevel, missingIncome);

        // Remaining personal-loan balances still drive the Levels 2–6 configured 34% pay-down action.
        BigDecimal personalLoansRemainingUzs = sumPersonalLoansRemainingUzs(month);

        // Allocation base = "left balance" = leftMoney − debtPayments: stable income minus
        // mandatory subscriptions, minus this month's monthly debt charge (bank installment +
        // 34% debt). Clamped at zero. The level / sub-level / tight-vs-comfortable split share
        // the same stable-income anchor, so the bucket %-amounts scale with what's left after debt.
        BigDecimal allocBaseUzs = clampZero(leftMoneyUzs.subtract(debtPaymentsUzs));

        // Paid-this-month per bucket (display currency), including "already paid" bucket marks.
        // The marks are also carried on their own so each line can say how much of its "paid"
        // figure never left a wallet — the one number the month view legitimately reports lower.
        BucketPaid marks = computeBucketMarks(month);
        BucketPaid paid = withBucketMarks(computePaidThisMonth(month, displayCurrency, false), marks);

        // Paid-this-month for the two debt-pay actions, so the guidance card can show
        // "you've paid X of Y this month" without the tier itself shifting.
        MonthPaid monthPaid = computeMonthPaid(month, displayCurrency);

        // Allocation tracking is dormant until the user-configured start month arrives.
        // Viewing a month before it still shows the tier (the client greys it) but asks for
        // NO payments — so setting a future start date can't demand a pay-now this month.
        YearMonth trackingStart = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth()) : null;
        boolean beforeTrackingStart = trackingStart != null && month.isBefore(trackingStart);

        // Mandatory subscriptions come FIRST: until every active subscription is paid for the
        // viewed month, the level / sub-level / action items / allocation stay withheld. "Paid"
        // is measured from real recorded payments (monthlyPaymentId) dated this month.
        List<OverviewTierResponse.PendingSubscription> pendingSubs =
                (missingIncome || beforeTrackingStart) ? List.of() : pendingSubscriptions(month);
        boolean subscriptionsPending = !pendingSubs.isEmpty();

        TierAllocation allocation;
        if (missingIncome) {
            allocation = notDefinedAllocation("page.plan.note.setIncome", Map.of(),
                    "Set monthly income to see allocation guidance.");
        } else if (beforeTrackingStart) {
            allocation = notDefinedAllocation("page.plan.note.trackingStarts",
                    Map.of("month", trackingStart.toString()),
                    "Allocation tracking starts " + monthLabel(trackingStart)
                            + " — guidance is paused until then.");
        } else if (subscriptionsPending) {
            allocation = notDefinedAllocation("page.plan.note.subscriptionsPending",
                    Map.of("month", month.toString()),
                    "Pay your mandatory subscription(s) for " + monthLabel(month)
                            + " first — your level and allocation unlock once they're covered.");
        } else {
            allocation = computeAllocation(level, subLevel, incomeUzs, allocBaseUzs, mandatoryUzs,
                    bankUzs, debt34Uzs, debtRatio, personalLoansRemainingUzs, plannedSetAsideUzs,
                    displayCurrency, paid, marks, monthPaid, upcomingChargeActions(month, displayCurrency));
        }

        return OverviewTierResponse.builder()
                .currency(displayCurrency)
                .income(incomeUzs)
                .mandatorySubscriptions(mandatoryUzs)
                .leftMoney(leftMoneyUzs)
                .allocationBase(allocBaseUzs)
                .debtPayments(debtPaymentsUzs)
                .debtBreakdown(OverviewTierResponse.DebtBreakdown.builder()
                        .bankLoans(bankUzs)
                        .loansTaken(loanTaken34Uzs)
                        .debts(debtRows34Uzs)
                        .build())
                .debtRatio(debtRatio)
                .level(level)
                .subLevel(subLevel)
                .levelLabel(levelLabel)
                .missingStableIncome(missingIncome)
                .beforeTrackingStart(beforeTrackingStart)
                .trackingStartMonth(trackingStart == null ? null : trackingStart.toString())
                .subscriptionsPending(subscriptionsPending)
                .pendingSubscriptions(pendingSubs)
                .allocation(allocation)
                .build();
    }

    /**
     * Active subscriptions not yet fully paid for {@code month}, measured from real recorded
     * payments (transactions carrying that {@code monthlyPaymentId}, dated within the month).
     * Amounts stay in each subscription's own currency — that's what the Pay modal expects.
     */
    private List<OverviewTierResponse.PendingSubscription> pendingSubscriptions(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<OverviewTierResponse.PendingSubscription> pending = new ArrayList<>();
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive())) continue;
            if (m.getAmount() == null || m.getCurrency() == null || m.getAmount().signum() <= 0) continue;
            BigDecimal paidThisMonth = transactionRepository.sumByMonthlyPaymentIdAndDateRange(m.getId(), start, end);
            if (paidThisMonth == null) paidThisMonth = BigDecimal.ZERO;
            // Include "already paid" marks for this subscription this month (no transaction recorded).
            for (MarkPaid mk : markPaidRepository.findByKindAndRefIdAndMonth("SUBSCRIPTION", m.getId(), start)) {
                paidThisMonth = paidThisMonth.add(mk.getAmount());
            }
            if (paidThisMonth.compareTo(m.getAmount()) >= 0) continue; // fully covered this month
            pending.add(OverviewTierResponse.PendingSubscription.builder()
                    .id(m.getId())
                    .name(m.getName())
                    .currency(m.getCurrency())
                    .amount(m.getAmount())
                    .paid(paidThisMonth)
                    .build());
        }
        return pending;
    }

    // ── Allocation ledger (cross-month backlog) ────────────────────────────────

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String[] LEDGER_BUCKETS = {"DONATION", "EMERGENCY", "INVESTMENTS"};
    private static final String[] LEDGER_LABELS = {"Donation", "Emergency", "Investments"};
    /**
     * Ledger width. The percentage arrays produced by {@link #computeLevel1Plan} and
     * {@link #bucketPercents} are still 4 wide — index 3 is the retired Stocks slot, kept
     * because AllocationRule still has the column — so every ledger loop must be bounded by
     * THIS, never by the length of a pct array. Hard-coding the bound is what broke the page
     * when Stocks was dropped from LEDGER_BUCKETS.
     */
    private static final int LEDGER_WIDTH = LEDGER_BUCKETS.length;

    /**
     * Running allocation ledger from the configured start month to {@code selected}. For each
     * month we recompute the tier scenario (so the % can vary as debts start/clear), apply it
     * to that month's "left balance" — the available money (carryover + income earned that
     * month) — to get the recommended amount, and net it against what was actually paid. The
     * balance is cumulative — overpaying a later month clears an earlier shortfall. Level/
     * sub-level stay anchored to stable income.
     */
    @Transactional(readOnly = true)
    public AllocationLedgerResponse getAllocationLedger(YearMonth selected, Currency display) {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        // Dormant before the configured start month: the ledger shows no dues at all, so a
        // future start date never surfaces a backlog or a pay-now for an un-started period.
        YearMonth trackingStart = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth()) : null;
        if (trackingStart != null && selected.isBefore(trackingStart)) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(trackingStart.toString())
                    .selectedMonth(selected.toString())
                    .beforeTrackingStart(true)
                    .trackingStartMonth(trackingStart.toString())
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

        YearMonth start = s.getAllocationTrackingStartMonth() != null
                ? YearMonth.from(s.getAllocationTrackingStartMonth())
                : YearMonth.now();
        if (start.isAfter(selected)) start = selected; // never iterate backwards

        if (missingIncome) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(start.toString())
                    .selectedMonth(selected.toString())
                    .missingStableIncome(true)
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

        // Mandatory subscriptions gate the tier's whole allocation (getTier), so they must gate
        // the ledger's dues too — otherwise the Overview header prints a concrete monthly ask
        // directly above the cards saying guidance is unavailable. Called once, never per month.
        if (!pendingSubscriptions(selected).isEmpty()) {
            return AllocationLedgerResponse.builder()
                    .currency(display)
                    .startMonth(start.toString())
                    .selectedMonth(selected.toString())
                    .subscriptionsPending(true)
                    .buckets(List.of())
                    .months(List.of())
                    .build();
        }

        BigDecimal stableUzs = s.getMonthlyStableIncome();
        BigDecimal mandatoryUzs = sumActiveSubscriptionsUzs();
        Integer level = computeLevel(stableUzs.subtract(mandatoryUzs));
        LocalDate today = LocalDate.now();

        BigDecimal[] totalRec = zeros();
        BigDecimal[] totalPaid = zeros();
        BigDecimal[] prevBalance = zeros();
        BigDecimal[] recSelected = zeros();
        BigDecimal[] paidSelected = zeros();
        BigDecimal[] markedSelected = zeros();
        String[] pctSelected = new String[LEDGER_WIDTH];
        BigDecimal bonusSelectedUzs = BigDecimal.ZERO;
        BigDecimal allocBaseSelectedUzs = BigDecimal.ZERO;
        String subLevelSelected = null;

        List<MonthBreakdown> months = new ArrayList<>();
        YearMonth earliestDue = null, latestDue = null;

        for (YearMonth m = start; !m.isAfter(selected); m = m.plusMonths(1)) {
            BigDecimal bankUzs = sumBankLoanMonthlyPaymentsUzs(today);
            BigDecimal loanInstallmentsUzs = bankUzs;
            BigDecimal debt34Uzs = sumDebt34Uzs(m);
            BigDecimal debtPaymentsUzs = loanInstallmentsUzs.add(debt34Uzs);
            BigDecimal debtRatio = stableUzs.signum() > 0 ? debtPaymentsUzs.divide(stableUzs, MC) : null;
            String subLevel = computeSubLevel(level, debtPaymentsUzs, debtRatio);

            BigDecimal bonusUzs = sumBonusIncomeUzs(m);

            // Bucket %s by scenario (Level 1 from stable income; Levels 2–6 from configured rules).
            // The base the %s multiply is the "left balance" = leftMoney − debtPayments (stable
            // income minus mandatory subscriptions, minus the monthly debt charge) — consistent
            // with the tier card.
            String[] pct;
            if (level != null && level == 1) {
                pct = computeLevel1Plan(stableUzs, mandatoryUzs, bankUzs, BigDecimal.ZERO,
                        debt34Uzs, debtRatio, minLeftoverUzs(1)).pct();
            } else {
                pct = bucketPercents(level, subLevel, stableUzs, mandatoryUzs, bankUzs,
                        sumPersonalLoansRemainingUzs(m));
            }
            BigDecimal allocBaseUzs = clampZero(stableUzs.subtract(mandatoryUzs).subtract(debtPaymentsUzs));

            // Marks read once per month and folded in here, so the loop never queries them twice.
            BucketPaid marksUzs = computeBucketMarks(m);
            BucketPaid paidUzs = withBucketMarks(computePaidThisMonth(m, Currency.UZS, false), marksUzs);
            BigDecimal[] paidArr = {paidUzs.donation(), paidUzs.emergency(), paidUzs.investments()};
            BigDecimal[] markedArr = {marksUzs.donation(), marksUzs.emergency(), marksUzs.investments()};

            boolean isSelected = m.equals(selected);
            boolean monthHasActivity = false;
            BigDecimal monthNetUzs = BigDecimal.ZERO;
            List<MonthBucketLine> lines = new ArrayList<>(LEDGER_WIDTH);

            for (int b = 0; b < LEDGER_WIDTH; b++) {
                BigDecimal recUzs = pct[b] == null ? BigDecimal.ZERO
                        : allocBaseUzs.multiply(new BigDecimal(pct[b]), MC).divide(HUNDRED, MC);
                BigDecimal paidB = paidArr[b];
                BigDecimal net = recUzs.subtract(paidB);

                totalRec[b] = totalRec[b].add(recUzs);
                totalPaid[b] = totalPaid[b].add(paidB);
                if (!isSelected) prevBalance[b] = prevBalance[b].add(net);
                else {
                    recSelected[b] = recUzs;
                    paidSelected[b] = paidB;
                    markedSelected[b] = markedArr[b];
                    pctSelected[b] = pct[b];
                }
                monthNetUzs = monthNetUzs.add(net);

                if (recUzs.signum() > 0 || paidB.signum() > 0) {
                    monthHasActivity = true;
                    lines.add(MonthBucketLine.builder()
                            .bucket(LEDGER_BUCKETS[b])
                            .percent(pct[b] == null ? null : new BigDecimal(pct[b]))
                            .recommended(recUzs)
                            .paid(paidB)
                            .net(net)
                            .build());
                }
            }

            if (isSelected) {
                bonusSelectedUzs = bonusUzs;
                allocBaseSelectedUzs = allocBaseUzs;
                subLevelSelected = subLevel;
            } else if (monthNetUzs.signum() > 0) {
                if (earliestDue == null) earliestDue = m;
                latestDue = m;
            }

            if (monthHasActivity) {
                months.add(MonthBreakdown.builder()
                        .month(m.toString())
                        .level(level)
                        .subLevel(subLevel)
                        .stableIncome(stableUzs)
                        .bonus(bonusUzs)
                        .allocationBase(allocBaseUzs)
                        .selected(isSelected)
                        .lines(lines)
                        .build());
            }
        }

        // Effective-% denominator is the selected month's allocation base (= available / left balance).
        BigDecimal incomeBaseSelUzs = allocBaseSelectedUzs;
        List<BucketLedger> buckets = new ArrayList<>(LEDGER_WIDTH);
        BigDecimal totalDueNowUzs = BigDecimal.ZERO;
        BigDecimal carriedPrevUzs = BigDecimal.ZERO;
        BigDecimal dueThisMonthUzs = BigDecimal.ZERO;

        for (int b = 0; b < LEDGER_WIDTH; b++) {
            BigDecimal balance = totalRec[b].subtract(totalPaid[b]);          // net through selected
            BigDecimal outstanding = balance.signum() > 0 ? balance : BigDecimal.ZERO;
            BigDecimal carried = prevBalance[b];                              // can be negative (ahead)
            BigDecimal effPct = (paidSelected[b].signum() > 0 && incomeBaseSelUzs.signum() > 0)
                    ? paidSelected[b].multiply(HUNDRED, MC).divide(incomeBaseSelUzs, MC)
                    : null;
            boolean over = paidSelected[b].compareTo(recSelected[b]) > 0;

            buckets.add(BucketLedger.builder()
                    .bucket(LEDGER_BUCKETS[b])
                    .label(LEDGER_LABELS[b])
                    .percent(pctSelected[b] == null ? null : new BigDecimal(pctSelected[b]))
                    .recommended(recSelected[b])
                    .paid(paidSelected[b])
                    .marked(markedSelected[b])
                    .carried(carried)
                    .outstanding(outstanding)
                    .effectivePercent(effPct)
                    .overAllocated(over)
                    .build());

            totalDueNowUzs = totalDueNowUzs.add(outstanding);
            if (carried.signum() > 0) carriedPrevUzs = carriedPrevUzs.add(carried);
            dueThisMonthUzs = dueThisMonthUzs.add(recSelected[b]);
        }

        return AllocationLedgerResponse.builder()
                .currency(display)
                .startMonth(start.toString())
                .selectedMonth(selected.toString())
                .missingStableIncome(false)
                .stableIncome(stableUzs)
                .bonusThisMonth(bonusSelectedUzs)
                .allocationBase(allocBaseSelectedUzs)
                .level(level)
                .subLevel(subLevelSelected)
                .dueThisMonth(dueThisMonthUzs)
                .carriedFromPrevious(carriedPrevUzs)
                .totalDueNow(totalDueNowUzs)
                .carriedStartMonth(earliestDue == null ? null : earliestDue.toString())
                .carriedEndMonth(latestDue == null ? null : latestDue.toString())
                .buckets(buckets)
                .months(months)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A ledger-width accumulator seeded with zeros. */
    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[LEDGER_WIDTH];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private BigDecimal sumActiveSubscriptionsUzs() {
        BigDecimal total = BigDecimal.ZERO;
        for (MonthlyPayment m : monthlyPaymentRepository.findAll()) {
            if (!Boolean.TRUE.equals(m.getActive())) continue;
            if (m.getAmount() == null || m.getCurrency() == null) continue;
            total = total.add(m.getAmount());
        }
        return total;
    }

    /** Bonus-tagged income received in {@code month}, normalised to UZS across currencies. */
    private BigDecimal sumBonusIncomeUzs(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        BigDecimal total = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBonusIncomeByCurrencyDateRange(c, start, end);
            if (sum != null && sum.signum() != 0) {
                total = total.add(sum);
            }
        }
        return total;
    }

    private BigDecimal sumBankLoanMonthlyPaymentsUzs(LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (BankLoan b : bankLoanRepository.findAll()) {
            if (b.getMonthlyPayment() == null || b.getMonthlyPayment().signum() <= 0) continue;
            // If the loan has ended, it no longer contributes.
            if (b.getEndDate() != null && b.getEndDate().isBefore(today)) continue;
            total = total.add(b.getMonthlyPayment());
        }
        return total;
    }

    /**
     * The mandatory monthly "debt" payment (34% rule). Per the owner, BOTH money borrowed from a
     * person (LoanTaken) and money owed (Debt) are debt — each pays 34% of its ORIGINAL total,
     * capped at the residual (final month), recurring until cleared. The only obligation that is
     * NOT a 34% debt is a bank loan (it has its own monthly installment). Gated by payment-start.
     */
    private BigDecimal sumDebt34Uzs(YearMonth month) {
        return sumLoanTaken34Uzs(month).add(sumDebtRows34Uzs(month));
    }

    /** 34% of original (capped) across active LoanTaken (money borrowed from people) rows. */
    private BigDecimal sumLoanTaken34Uzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            // A loan with an agreed monthly plan costs that, not 34% of the whole sum. Still
            // capped at what is actually left, so the final month tops out and the debt clears.
            BigDecimal charge = plannedOrDefaultCharge(l);
            if (charge.signum() <= 0) continue;
            if (!hasStartedBy(l.getPaymentStartDate(), month)) continue;
            total = total.add(charge);
        }
        return total;
    }

    /**
     * The monthly charge for one borrowed loan: the user's own repayment plan when they set one
     * (capped at the residual), otherwise the default 34%-of-original rule.
     */
    static BigDecimal plannedOrDefaultCharge(LoanTaken l) {
        BigDecimal planned = l.getPlannedMonthlyPayment();
        if (planned == null || planned.signum() <= 0) {
            return debtMonthlyCharge(l.getTotalAmount(), l.getPaidAmount());
        }
        BigDecimal residual = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
        if (residual.signum() <= 0) return BigDecimal.ZERO;
        return planned.min(residual);
    }

    /**
     * This month's total across loans the user has put on a repayment PLAN. This is the
     * "set aside" figure: it belongs in the allocation as its own ask, not folded into the
     * 34% pay-down, because it is a number the user chose rather than one the rule imposed.
     */
    private BigDecimal sumLoanTakenPlannedUzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            if (l.getPlannedMonthlyPayment() == null || l.getPlannedMonthlyPayment().signum() <= 0) continue;
            if (!hasStartedBy(l.getPaymentStartDate(), month)) continue;
            total = total.add(plannedOrDefaultCharge(l));
        }
        return total;
    }

    /**
     * Repayments that exist but have not STARTED yet, as informational action items.
     *
     * <p>A loan whose payment-start month is still in the future is excluded from every figure
     * on this page — no 34% charge, no set-aside. That is correct, but silently correct: the
     * user sets a 500,000/mo plan, sees nothing appear anywhere, and reasonably concludes the
     * plan was not saved. Naming the money and the month it begins costs one line and removes
     * the whole class of confusion.
     *
     * <p>These carry no {@code action}, so they never lock the allocation or ask to be paid.
     */
    private List<ActionItem> upcomingChargeActions(YearMonth month, Currency cur) {
        List<ActionItem> upcoming = new ArrayList<>();
        for (LoanTaken l : loanTakenRepository.findAll()) {
            LocalDate startsOn = l.getPaymentStartDate();
            if (startsOn == null || hasStartedBy(startsOn, month)) continue;
            BigDecimal charge = plannedOrDefaultCharge(l);
            if (charge.signum() <= 0) continue;
            boolean planned = l.getPlannedMonthlyPayment() != null
                    && l.getPlannedMonthlyPayment().signum() > 0;
            String name = orEmpty(l.getLenderName());
            String amount = formatNumber(charge) + " " + cur;
            YearMonth starts = YearMonth.from(startsOn);
            upcoming.add(info(
                    planned ? "page.plan.note.loanPlanNotStarted" : "page.plan.note.loanChargeNotStarted",
                    Map.of("name", name, "amount", amount, "month", starts.toString()),
                    name + ": "
                    + (planned ? "your plan of " : "the 34% charge of ")
                    + amount + "/mo starts "
                    + monthLabel(starts)
                    + " — not counted this month. Edit the loan to start it sooner."));
        }
        for (Debt d : debtRepository.findAll()) {
            LocalDate startsOn = d.getPaymentStartDate();
            if (startsOn == null || hasStartedBy(startsOn, month)) continue;
            BigDecimal charge = debtMonthlyCharge(d.getTotalAmount(), d.getPaidAmount());
            if (charge.signum() <= 0) continue;
            String name = orEmpty(d.getCreditorName());
            String amount = formatNumber(charge) + " " + cur;
            YearMonth starts = YearMonth.from(startsOn);
            upcoming.add(info("page.plan.note.debtChargeNotStarted",
                    Map.of("name", name, "amount", amount, "month", starts.toString()),
                    name + ": the 34% charge of "
                    + amount + "/mo starts "
                    + monthLabel(starts)
                    + " — not counted this month. Edit the debt to start it sooner."));
        }
        return upcoming;
    }

    /** 34% of original (capped) across active Debt rows. */
    private BigDecimal sumDebtRows34Uzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Debt d : debtRepository.findAll()) {
            BigDecimal charge = debtMonthlyCharge(d.getTotalAmount(), d.getPaidAmount());
            if (charge.signum() <= 0) continue;
            if (!hasStartedBy(d.getPaymentStartDate(), month)) continue;
            total = total.add(charge);
        }
        return total;
    }

    /**
     * 34% of the ORIGINAL total, capped at the outstanding residual so the final month tops
     * out and the debt then clears (C4). Returns zero once paidAmount &gt;= totalAmount.
     * Pure — unit-tested directly.
     */
    static BigDecimal debtMonthlyCharge(BigDecimal totalAmount, BigDecimal paidAmount) {
        BigDecimal total = nullToZero(totalAmount);
        BigDecimal residual = total.subtract(nullToZero(paidAmount));
        if (residual.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal charge = total.multiply(PERSONAL_LOAN_PAYDOWN_RATE, MC);
        return charge.compareTo(residual) > 0 ? residual : charge;
    }

    /**
     * Whether a loan/debt's monthly contribution counts toward the tier for the viewed
     * month. True when its payment-start month is on or before {@code month}. Legacy rows
     * (null start) always count, preserving prior behaviour.
     */
    private boolean hasStartedBy(LocalDate paymentStartDate, YearMonth month) {
        if (paymentStartDate == null) return true;
        return !YearMonth.from(paymentStartDate).isAfter(month);
    }

    private Integer computeLevel(BigDecimal leftMoneyUzs) {
        for (int i = 0; i < LEVEL_BREAKPOINTS_UZS.length; i++) {
            if (leftMoneyUzs.compareTo(LEVEL_BREAKPOINTS_UZS[i]) < 0) return i + 1;
        }
        return null; // above tier ceiling
    }

    /**
     * Debt-based sub-level for ANY level: {level}.1 no debt, .2 manageable (ratio &lt; 70%),
     * .3 heavy (≥ 70%). Level 1 keeps its hard-coded scenario logic downstream; Levels 2–6
     * use these sub-levels to look up the user-configured allocation rules.
     */
    private String computeSubLevel(Integer level, BigDecimal debtTotalUzs, BigDecimal debtRatio) {
        if (level == null) return null;
        String suffix;
        if (debtTotalUzs.signum() == 0) suffix = "1";
        // Strict: debt payments > 70% of income → heavy (.3); exactly 70% stays manageable (.2).
        else if (debtRatio != null && debtRatio.compareTo(DEBT_RATIO_THRESHOLD) <= 0) suffix = "2";
        else suffix = "3";
        return level + "." + suffix;
    }

    /** Human label for a debt sub-level suffix (".1/.2/.3"). */
    private String subLevelDebtLabel(String subLevel) {
        if (subLevel == null) return "";
        if (subLevel.endsWith(".1")) return "no debt";
        if (subLevel.endsWith(".2")) return "manageable debt (≤ 70% of income)";
        if (subLevel.endsWith(".3")) return "heavy debt (> 70% of income)";
        return "";
    }

    private String computeLevelLabel(Integer level, String subLevel, boolean missingIncome) {
        if (missingIncome) return "Set monthly income to compute tier";
        if (level == null) return "Above tier 6";
        if (subLevel != null) return "Level " + subLevel;
        return "Level " + level;
    }

    private static BigDecimal nullToZero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    /**
     * Total REMAINING amount across LoanTaken + Debt records (personal loans only,
     * not bank installments). Used to compute the recommended 34% pay-down for
     * sub-levels 1.2.2 / 1.2.3 / 1.3 — distinct from the derived monthly contribution
     * which drives the sub-level ratio.
     */
    private BigDecimal sumPersonalLoansRemainingUzs(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoanTaken l : loanTakenRepository.findAll()) {
            // A loan on a plan is asked for at its plan amount instead; charging 34% of its
            // remaining here as well would demand the money twice.
            if (l.getPlannedMonthlyPayment() != null && l.getPlannedMonthlyPayment().signum() > 0) continue;
            BigDecimal remaining = nullToZero(l.getTotalAmount()).subtract(nullToZero(l.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(l.getPaymentStartDate(), month)) continue;
            total = total.add(remaining);
        }
        for (Debt d : debtRepository.findAll()) {
            BigDecimal remaining = nullToZero(d.getTotalAmount()).subtract(nullToZero(d.getPaidAmount()));
            if (remaining.signum() <= 0) continue;
            if (!hasStartedBy(d.getPaymentStartDate(), month)) continue;
            total = total.add(remaining);
        }
        return total;
    }

    // ── Per-bucket paid-this-month + history ──────────────────────────────────

    /**
     * Holds paid-this-month totals in the display currency for each bucket. Computed
     * once per tier request and threaded through into the allocation lines.
     */
    record BucketPaid(BigDecimal donation, BigDecimal emergency, BigDecimal investments, BigDecimal stocks,
                      BigDecimal savings) {}

    /**
     * Paid-this-month sums for the two debt-pay actions, in display currency.
     * Drives the "Paid X of Y" progress strip under the action items.
     */
    record MonthPaid(BigDecimal bankInstallments, BigDecimal personalLoanRepayments) {}

    private MonthPaid computeMonthPaid(YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        BigDecimal bank = BigDecimal.ZERO;
        BigDecimal personal = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal bankSum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.BANK_LOAN_PAYMENT, c, start, end);
            if (bankSum != null && bankSum.signum() > 0) {
                bank = bank.add(bankSum);
            }
            BigDecimal repaySum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.LOAN_REPAYMENT, c, start, end);
            if (repaySum != null && repaySum.signum() > 0) {
                personal = personal.add(repaySum);
            }
        }
        // "Already paid" marks (no transaction) for bank installments and personal loans / debts.
        for (MarkPaid m : markPaidRepository.findByMonth(start)) {
            switch (m.getKind() == null ? "" : m.getKind()) {
                case "BANK" -> bank = bank.add(m.getAmount());
                case "PERSONAL_LOAN", "DEBT" ->
                        personal = personal.add(m.getAmount());
                default -> { }
            }
        }
        return new MonthPaid(bank, personal);
    }

    // ── "Already paid" marks ──────────────────────────────────────────────────

    /**
     * The "already paid" BUCKET marks for {@code month}, on their own — money the user declared
     * settled without recording a transaction. Every screen that quotes a bucket total needs
     * this same sum, either folded in (the plan's figure) or named beside the recorded figure
     * (the month view), so it lives in one place rather than being re-derived per caller.
     *
     * <p>Savings is always zero: SAVINGS is not a markable bucket (see FinanceService.MARK_BUCKETS).
     */
    BucketPaid computeBucketMarks(YearMonth month) {
        BigDecimal donation = BigDecimal.ZERO, emergency = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO, stocks = BigDecimal.ZERO;
        for (MarkPaid m : markPaidRepository.findByMonth(month.atDay(1))) {
            if (!"BUCKET".equals(m.getKind()) || m.getBucket() == null) continue;
            BigDecimal amt = m.getAmount();
            switch (m.getBucket()) {
                case "DONATION" -> donation = donation.add(amt);
                case "EMERGENCY" -> emergency = emergency.add(amt);
                case "INVESTMENTS" -> investments = investments.add(amt);
                case "STOCKS" -> stocks = stocks.add(amt);
                default -> { }
            }
        }
        return new BucketPaid(donation, emergency, investments, stocks, BigDecimal.ZERO);
    }

    /** Σ of every BUCKET mark for a month — the part of a bucket total that moved no money. */
    static BigDecimal markedTotal(BucketPaid marks) {
        return marks.donation().add(marks.emergency()).add(marks.investments()).add(marks.stocks());
    }

    /**
     * Fold "already paid" BUCKET marks into a recorded-payments BucketPaid. Package-private and
     * static because {@link MonthCloseService} folds the same marks onto a CLOSED month's frozen
     * snapshot columns — the fold has to be one piece of code, or the plan and the month view
     * drift apart again the moment they each write their own addition.
     */
    static BucketPaid withBucketMarks(BucketPaid base, BucketPaid marks) {
        return new BucketPaid(
                base.donation().add(marks.donation()),
                base.emergency().add(marks.emergency()),
                base.investments().add(marks.investments()),
                base.stocks().add(marks.stocks()),
                base.savings());
    }

    /** One INVESTMENT transaction rendered as a bucket-history row. */
    private uz.tracker.trackerproject.dto.response.BucketPayment investmentBucketRow(
            Transaction t, String bucket) {
        return uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                .id(t.getId())
                .bucket(bucket)
                .date(t.getTransactionDate())
                .amount(t.getAmount())
                .nativeAmount(t.getAmount())
                .nativeCurrency(t.getCurrency())
                .label(t.getDescription())
                .description(t.getNote())
                .build();
    }

    /**
     * Does this INVESTMENT transaction fund a savings goal (rather than a plain investment)?
     *
     * <p>The answer is whatever was RECORDED on the row when the money moved. It used to be
     * re-derived here from the holding's current savingsGoal flag, which stays editable for the
     * whole life of a holding — so ticking that one checkbox emptied the Investments bucket of a
     * month that was already closed, on Plan, on the Investments tab and on Home, while Months
     * went on quoting the frozen month-close snapshot. Ticking "emergency fund" did the same in
     * reverse (it clears savingsGoal). See {@link AllocationBucket}.
     */
    private boolean isSavingsGoalTx(Transaction t) {
        if (t.getAllocationBucket() != null) {
            return AllocationBucket.SAVINGS.equals(t.getAllocationBucket());
        }
        // Rows written before the column existed: `ddl-auto=update` adds a column but never fills
        // it, so DataSeeder back-fills the history on boot and this derivation stays as the
        // permanent safety net for anything that escapes it — never the normal path.
        // A contribution row carries investmentId; the row that CREATED the holding is instead
        // referenced by the investment's originatingTransactionId. Unlinked rows (a bare
        // INVESTMENT transaction whose auto-created record is gone) count as a plain investment.
        Investment target = null;
        if (t.getInvestmentId() != null) {
            target = investmentRepository.findById(t.getInvestmentId()).orElse(null);
        }
        if (target == null) {
            target = investmentRepository.findByOriginatingTransactionId(t.getId()).orElse(null);
        }
        return target != null && Boolean.TRUE.equals(target.getSavingsGoal());
    }

    // ── Allocation preview (what would this draft transaction do?) ────────────

    /**
     * Which bucket, if any, a sub-type funds. This is the SAME routing
     * {@link #computePaidThisMonth} uses, kept in one place so the preview can never
     * promise a bucket the accounting wouldn't actually credit.
     */
    private String bucketForSubType(uz.tracker.trackerproject.enums.TransactionSubType subType, Long investmentId) {
        if (subType == null) return null;
        return switch (subType) {
            case DONATION -> "DONATION";
            case EMERGENCY_CONTRIBUTION -> "EMERGENCY";
            case STOCK_PURCHASE -> null; // stocks are no longer an allocation bucket
            case INVESTMENT -> {
                Investment target = investmentId == null ? null
                        : investmentRepository.findById(investmentId).orElse(null);
                // An emergency-flagged target books EMERGENCY_CONTRIBUTION at write time,
                // so the preview must say Emergency, not Investments.
                if (target != null && Boolean.TRUE.equals(target.getEmergencyFund())) yield "EMERGENCY";
                yield (target != null && Boolean.TRUE.equals(target.getSavingsGoal()))
                        ? "SAVINGS" : "INVESTMENTS";
            }
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public uz.tracker.trackerproject.dto.response.AllocationPreviewResponse previewAllocation(
            uz.tracker.trackerproject.dto.request.AllocationPreviewRequest req, Currency display) {

        String bucket = bucketForSubType(req.getSubType(), req.getInvestmentId());
        if (bucket == null) {
            return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                    .applicable(false)
                    .message("This transaction doesn't fund any allocation bucket.")
                    .build();
        }

        YearMonth month = YearMonth.from(req.getTransactionDate());
        OverviewTierResponse tier = getTier(month, display);
        BigDecimal amount = req.getAmount() == null ? BigDecimal.ZERO : req.getAmount();

        TierAllocation.AllocationLine line = tier.getAllocation() == null ? null
                : tier.getAllocation().getLines().stream()
                        .filter(l -> bucket.equals(l.getBucket()))
                        .findFirst().orElse(null);

        String label = line != null ? line.getLabel() : bucket;

        // No line, or a 0% bucket at this tier: the money still lands there, it just isn't
        // being asked of the user this month. Say so rather than showing a target of zero.
        if (line == null || !line.isRecommended()) {
            return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                    .applicable(true)
                    .bucket(bucket)
                    .label(label)
                    .bucketNotRecommended(true)
                    .amount(amount)
                    .paidBefore(line == null ? BigDecimal.ZERO : line.getPaidAmount())
                    .paidAfter((line == null ? BigDecimal.ZERO : line.getPaidAmount()).add(amount))
                    .message("Counts toward " + label + ", which isn't required at your current tier"
                            + " — it's recorded, but nothing is expected this month.")
                    .build();
        }

        BigDecimal recommended = nullToZero(line.getMinAmount());
        BigDecimal paidBefore = nullToZero(line.getPaidAmount());
        BigDecimal paidAfter = paidBefore.add(amount);
        BigDecimal remainingBefore = clampZero(recommended.subtract(paidBefore));
        BigDecimal remainingAfter = clampZero(recommended.subtract(paidAfter));
        boolean completes = remainingBefore.signum() > 0 && remainingAfter.signum() == 0;

        String msg = completes
                ? "This covers the rest of your " + label + " for " + monthLabel(month) + "."
                : remainingAfter.signum() == 0
                    ? label + " was already covered for " + monthLabel(month) + "."
                    : "Leaves " + remainingAfter + " still to put aside for " + label + ".";

        return uz.tracker.trackerproject.dto.response.AllocationPreviewResponse.builder()
                .applicable(true)
                .bucket(bucket)
                .label(label)
                .recommended(recommended)
                .paidBefore(paidBefore)
                .amount(amount)
                .paidAfter(paidAfter)
                .remainingBefore(remainingBefore)
                .remainingAfter(remainingAfter)
                .completesBucket(completes)
                .message(msg)
                .build();
    }

    /**
     * Paid-this-month per bucket, marks included. This is the figure every screen quotes, so it
     * is the default; the ONE caller that must not see marks (the irreversible month-close
     * snapshot) opts out explicitly via the three-argument form below.
     */
    BucketPaid computePaidThisMonth(YearMonth month, Currency displayCurrency) {
        return computePaidThisMonth(month, displayCurrency, true);
    }

    /**
     * @param includeMarks fold this month's "already paid" BUCKET marks in. True for anything the
     *        user reads as "paid" (tier, ledger, month summary, close preview). False only for the
     *        month-close snapshot: a mark moves no tracked money, so counting it there would
     *        overstate taggedTotal and silently shrink {@code everydaySpend = totalSpent − tagged}.
     */
    BucketPaid computePaidThisMonth(YearMonth month, Currency displayCurrency, boolean includeMarks) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        BigDecimal donation = BigDecimal.ZERO;
        for (Donation d : donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)) {
            donation = donation.add(d.getAmount());
        }

        // Emergency bucket = EMERGENCY_CONTRIBUTION transactions (the actual money out — covers both
        // the Emergencies CRUD, which mirrors a tx, AND a generic-modal EMERGENCY_CONTRIBUTION row)
        // plus emergency-fund-flagged investments below. Counting the tx (not the Emergency entity)
        // means a contribution recorded via the transaction modal is no longer missed.
        BigDecimal emergency = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.EMERGENCY_CONTRIBUTION, c, start, end);
            if (sum != null && sum.signum() > 0) emergency = emergency.add(sum);
        }

        // Investments + Savings are counted from INVESTMENT transactions BY TRANSACTION DATE, the
        // same way Emergency and Stocks are. Counting the Investment entity by purchaseDate instead
        // (the old behaviour) credited every later top-up to the month the holding was first bought
        // — so money spent this month was invisible, a past (possibly closed) month silently grew,
        // and a top-up aimed at a savings goal or emergency fund landed in no bucket at all.
        // Money that never moved (opening balances, "None"/noWallet contributions) books no
        // transaction, so it is now excluded automatically rather than by an explicit flag check.
        BigDecimal investments = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        for (Transaction t : transactionRepository
                .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                        uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)) {
            BigDecimal amt = t.getAmount();
            if (amt == null || amt.signum() <= 0) continue;
            if (isSavingsGoalTx(t)) savings = savings.add(amt);
            else investments = investments.add(amt);
        }

        // Stocks are tracked in a separate app — the bucket is funded by STOCK_PURCHASE transactions.
        BigDecimal stocks = BigDecimal.ZERO;
        for (Currency c : Currency.reporting()) {
            BigDecimal sum = transactionRepository.sumBySubTypeCurrencyDateRange(
                    uz.tracker.trackerproject.enums.TransactionSubType.STOCK_PURCHASE, c, start, end);
            if (sum != null && sum.signum() > 0) stocks = stocks.add(sum);
        }

        BucketPaid recorded = new BucketPaid(donation, emergency, investments, stocks, savings);
        return includeMarks ? withBucketMarks(recorded, computeBucketMarks(month)) : recorded;
    }

    /**
     * Per-bucket payment history for a month. Returns transactions in the entity's
     * "natural" date field (donationDate / date / purchaseDate), normalised into a
     * common BucketPayment row shape so the frontend can render uniformly, plus the
     * month's "already paid" marks — the rows must add up to the bucket total shown
     * above them, and the plan's total counts marks.
     */
    @Transactional(readOnly = true)
    public List<uz.tracker.trackerproject.dto.response.BucketPayment> getBucketPayments(
            String bucket, YearMonth month, Currency displayCurrency) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<uz.tracker.trackerproject.dto.response.BucketPayment> rows = new ArrayList<>();
        String key = bucket.toUpperCase();

        switch (key) {
            case "DONATION" -> donationRepository.findByDonationDateBetweenOrderByDonationDateDesc(start, end)
                    .forEach(d -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(d.getId())
                            .bucket("DONATION")
                            .date(d.getDonationDate())
                            .amount(d.getAmount())
                            .nativeAmount(d.getAmount())
                            .nativeCurrency(d.getCurrency())
                            .label(Boolean.TRUE.equals(d.getAnonymous()) ? "Anonymous" : d.getRecipientName())
                            .description(d.getDescription())
                            .build()));
            case "EMERGENCY" -> {
                transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                                uz.tracker.trackerproject.enums.TransactionSubType.EMERGENCY_CONTRIBUTION, start, end)
                        .forEach(t -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                                .id(t.getId())
                                .bucket("EMERGENCY")
                                .date(t.getTransactionDate())
                                .amount(t.getAmount())
                                .nativeAmount(t.getAmount())
                                .nativeCurrency(t.getCurrency())
                                .label("Emergency fund")
                                .description(t.getDescription())
                                .build()));
                // Emergency-fund investment funding (initial + top-ups) books EMERGENCY_CONTRIBUTION
                // transactions, already listed above — no separate by-entity listing needed.
            }
            // Both read the SAME source as computePaidThisMonth (INVESTMENT transactions by
            // transaction date, split by the bucket each row recorded when it was written) so the
            // history rows always add up to the bucket total shown above them.
            case "INVESTMENTS" -> transactionRepository
                    .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)
                    .stream().filter(t -> !isSavingsGoalTx(t))
                    .forEach(t -> rows.add(investmentBucketRow(t, "INVESTMENTS")));
            case "SAVINGS" -> transactionRepository
                    .findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.INVESTMENT, start, end)
                    .stream().filter(this::isSavingsGoalTx)
                    .forEach(t -> rows.add(investmentBucketRow(t, "SAVINGS")));
            case "STOCKS" -> transactionRepository.findBySubTypeAndTransactionDateBetweenOrderByTransactionDateDesc(
                            uz.tracker.trackerproject.enums.TransactionSubType.STOCK_PURCHASE, start, end)
                    .forEach(t -> rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                            .id(t.getId())
                            .bucket("STOCKS")
                            .date(t.getTransactionDate())
                            .amount(t.getAmount())
                            .nativeAmount(t.getAmount())
                            .nativeCurrency(t.getCurrency())
                            .label("Stocks")
                            .description(t.getDescription())
                            .build()));
            default -> throw new IllegalArgumentException("Unknown bucket: " + bucket);
        }

        // The marks the tier folds into "Paid" belong here too: without them the panel's rows can
        // never reach the figure on the card above, which is the whole reason the user opened it.
        // They carry a MarkPaid id and moved no money, so `marked` tells the client not to treat
        // the row as an editable Donation / Transaction.
        for (MarkPaid m : markPaidRepository.findByMonth(start)) {
            if (!"BUCKET".equals(m.getKind()) || !key.equals(m.getBucket())) continue;
            rows.add(uz.tracker.trackerproject.dto.response.BucketPayment.builder()
                    .id(m.getId())
                    .bucket(key)
                    .date(m.getMonth())
                    .amount(m.getAmount())
                    .nativeAmount(m.getAmount())
                    .nativeCurrency(m.getCurrency())
                    .label("Marked as already paid")
                    .description(m.getNote())
                    .marked(true)
                    .build());
        }
        rows.sort(Comparator.comparing(
                uz.tracker.trackerproject.dto.response.BucketPayment::getDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // ── Allocation guidance ───────────────────────────────────────────────────

    /**
     * Compute the allocation recommendation for a tier. Level 1 has hard-coded rules
     * per the owner's spec; Levels 2-6 return a "not yet defined" stub until the user
     * configures their own percentages.
     */
    private TierAllocation computeAllocation(
            Integer level, String subLevel,
            BigDecimal incomeUzs, BigDecimal allocBaseUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs,
            BigDecimal debt34Uzs, BigDecimal debtRatio, BigDecimal personalLoansRemainingUzs,
            BigDecimal plannedSetAsideUzs,
            Currency displayCurrency, BucketPaid paid, BucketPaid marks, MonthPaid monthPaid,
            List<ActionItem> upcoming) {

        if (level == null) {
            return notDefinedAllocation("page.plan.note.aboveTierCeiling", Map.of(),
                    "You're above the current tier ceiling — guidance not defined yet.");
        }
        if (level != 1) {
            return computeConfiguredAllocation(level, subLevel, allocBaseUzs, displayCurrency,
                    bankMonthlyUzs, personalLoansRemainingUzs, plannedSetAsideUzs, paid, marks,
                    monthPaid, upcoming);
        }

        // Level-1 engine: the SCENARIO (case A/B/C, tight-vs-comfortable split, bucket %s) is still
        // selected from stable income per the owner's spec (decisions D1–D4); but the base the
        // percentages multiply is THIS MONTH'S AVAILABLE money ("left balance" = carryover + income),
        // per the owner's 2026-06-07 change. loanInstallments = bank only → ZERO in the 4th slot,
        // since borrowed money is debt (34%).
        Level1Plan plan = computeLevel1Plan(incomeUzs, mandatoryUzs, bankMonthlyUzs, BigDecimal.ZERO,
                debt34Uzs, debtRatio, minLeftoverUzs(1));
        String[] p = plan.pct();
        List<AllocationLine> lines = percentLines(allocBaseUzs, displayCurrency, paid, marks,
                p[0], p[1], p[2]);

        // Action targets (display currency). Bank installments pay via PayBankInstallmentModal; the
        // 34%-of-debt action (borrowed money + debts) pays via PayPersonalLoanModal.
        BigDecimal bankTarget = bankMonthlyUzs;
        BigDecimal personalUzs = nullToZero(debt34Uzs);
        BigDecimal personalTarget = personalUzs;

        List<ActionItem> actions = level1Actions(plan, monthPaid, bankTarget,
                bankMonthlyUzs, personalTarget, personalUzs, plannedSetAsideUzs, displayCurrency, upcoming);

        return TierAllocation.builder()
                .scenarioKey(plan.scenarioKey())
                .scenarioLabel(scenarioLabel(plan.scenarioKey()))
                .lines(lines)
                .actions(actions)
                .allocationLocked(isAllocationLocked(actions))
                .build();
    }

    /**
     * Owner-spec Level-1 case selection, calc base, and bucket percentages. Pure (no repos / fields)
     * so it can be unit-tested directly. All amounts in UZS.
     *   leftBalance = income − mandatory; loanInstallments = bank loans (params 3+4, production
     *   passes bank in 3 and 0 in 4); debt34 = 34% of borrowed money (LoanTaken) + debts (Debt).
     *   A  (no debt)         → base leftBalance,          10/5/15/5
     *   C  (ratio &gt; 70%)     → base left−loan−debt34,    2/0/0/0
     *   B3 (loan AND debt)   → base left−loan−debt34,     5/0/5/0  (no 5M split)
     *   B1 (loan only)       → base left−loan,            &lt;5M 5/2/8/0 · ≥5M 7/3/10/3
     *   B2 (debt only)       → base left−debt34,          &lt;5M 5/2/8/0 · ≥5M 7/3/10/3
     */
    static Level1Plan computeLevel1Plan(BigDecimal incomeUzs, BigDecimal mandatoryUzs,
            BigDecimal bankMonthlyUzs, BigDecimal loanTakenUzs, BigDecimal debt34Uzs,
            BigDecimal debtRatio, BigDecimal cutoffUzs) {
        BigDecimal leftBalance = nullToZero(incomeUzs).subtract(nullToZero(mandatoryUzs));
        BigDecimal loanInstallments = nullToZero(bankMonthlyUzs).add(nullToZero(loanTakenUzs));
        BigDecimal debt34 = nullToZero(debt34Uzs);
        boolean hasLoan = loanInstallments.signum() > 0;
        boolean hasDebt = debt34.signum() > 0;

        if (!hasLoan && !hasDebt) {
            return new Level1Plan("1.1", new String[]{"10", "5", "15", "5"},
                    clampZero(leftBalance), false, false);
        }
        boolean heavy = debtRatio != null && debtRatio.compareTo(DEBT_RATIO_THRESHOLD) > 0; // strict > 70%
        if (heavy) {
            BigDecimal base = clampZero(leftBalance.subtract(loanInstallments).subtract(debt34));
            return new Level1Plan("1.3", new String[]{"2", null, null, null}, base, hasLoan, hasDebt);
        }
        if (hasLoan && hasDebt) {
            BigDecimal base = clampZero(leftBalance.subtract(loanInstallments).subtract(debt34));
            return new Level1Plan("1.2.3", new String[]{"5", null, "5", null}, base, true, true);
        }
        if (hasLoan) {
            BigDecimal base = clampZero(leftBalance.subtract(loanInstallments));
            boolean tight = base.compareTo(nullToZero(cutoffUzs)) < 0;
            return new Level1Plan(tight ? "1.2.1.tight" : "1.2.1.comfortable",
                    tight ? new String[]{"5", "2", "8", null} : new String[]{"7", "3", "10", "3"},
                    base, true, false);
        }
        BigDecimal base = clampZero(leftBalance.subtract(debt34));
        boolean tight = base.compareTo(nullToZero(cutoffUzs)) < 0;
        return new Level1Plan(tight ? "1.2.2.tight" : "1.2.2.comfortable",
                tight ? new String[]{"5", "2", "8", null} : new String[]{"7", "3", "10", "3"},
                base, false, true);
    }

    /** Result of the Level-1 engine: scenario key, bucket percentages, and the calc base (UZS). */
    record Level1Plan(String scenarioKey, String[] pct, BigDecimal calcBaseUzs,
                      boolean hasLoan, boolean hasDebt) {}

    private static BigDecimal clampZero(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    /** Level-1 action items per scenario (keeps PAY_BANK / PAY_PERSONAL_LOAN keys for the UI). */
    private List<ActionItem> level1Actions(Level1Plan plan, MonthPaid monthPaid,
            BigDecimal bankTarget, BigDecimal bankMonthlyUzs,
            BigDecimal personalTarget, BigDecimal personalUzs,
            BigDecimal plannedSetAsideUzs, Currency cur, List<ActionItem> upcoming) {
        String key = plan.scenarioKey();
        // 1.1 is the debt-free scenario, so it asks for nothing — but a repayment that merely
        // has not STARTED yet is still worth showing, or the money looks as if it vanished.
        if ("1.1".equals(key)) return List.copyOf(upcoming);
        List<ActionItem> actions = new ArrayList<>();
        if (bankMonthlyUzs.signum() > 0) {
            actions.add(payBank(monthPaid, bankTarget));
        }
        // The set-aside is money the user committed to themselves; the 34% pay-down is the
        // rule's demand on everything else. Showing them as one number hid the plan entirely.
        if (plannedSetAsideUzs.signum() > 0) {
            actions.add(setAside(plannedSetAsideUzs, cur, monthPaid));
        }
        BigDecimal ruleTarget = personalTarget.subtract(plannedSetAsideUzs);
        if (personalUzs.signum() > 0 && ruleTarget.signum() > 0) {
            String amount = formatNumber(ruleTarget) + " " + cur;
            actions.add(ActionItem.builder()
                    .text("Pay at least 34% of your debts / borrowed money (~ "
                            + amount + ") this month.")
                    .code("page.plan.action.payDebts34")
                    .params(Map.of("amount", amount))
                    .action("PAY_PERSONAL_LOAN")
                    .paid(monthPaid.personalLoanRepayments())
                    .target(ruleTarget)
                    .unlockThreshold(ruleTarget)
                    .build());
        }
        switch (key) {
            case "1.2.1.tight", "1.2.2.tight" ->
                    actions.add(info("page.plan.note.tight", Map.of(),
                            "Less than 5M UZS remains after debt — slim allocations until things ease up."));
            case "1.2.1.comfortable", "1.2.2.comfortable" ->
                    actions.add(info("page.plan.note.comfortable", Map.of(),
                            "5M+ UZS remains after debt — higher allocations apply."));
            case "1.2.3" ->
                    actions.add(info("page.plan.note.loanAndDebt", Map.of(),
                            "Both loan and debt — emergency & stocks are skipped this tier; focus on debt."));
            case "1.3" ->
                    actions.add(info("page.plan.note.heavyDebt", Map.of(),
                            "Heavy debt (> 70% of income): only a 2% donation this month. "
                            + "You may withdraw from the emergency fund if the situation gets really bad."));
            default -> { }
        }
        actions.addAll(upcoming);
        return actions;
    }

    private String scenarioLabel(String key) {
        if (key == null) return "Guidance not yet defined for this tier";
        return switch (key) {
            case "1.1" -> "Level 1.1 — no debts";
            case "1.2.1.tight" -> "Level 1.2 — bank loan only, tight (< 5M UZS after debt)";
            case "1.2.1.comfortable" -> "Level 1.2 — bank loan only, comfortable (≥ 5M UZS after debt)";
            case "1.2.2.tight" -> "Level 1.2 — debts only, tight (< 5M UZS after debt)";
            case "1.2.2.comfortable" -> "Level 1.2 — debts only, comfortable (≥ 5M UZS after debt)";
            case "1.2.3" -> "Level 1.2 — bank loan + debts (fixed allocation)";
            case "1.3" -> "Level 1.3 — heavy debt (> 70% of income)";
            default -> "Guidance not yet defined for this tier";
        };
    }

    // ── Levels 2–6: user-configured allocation rules ────────────────────────────

    /**
     * Build the allocation for a Level 2–6 sub-level from the user's configured rule.
     * Unconfigured → a stub prompting them to set it (the frontend shows the editor button).
     * Debt-pay actions are still surfaced when the sub-level carries debt.
     */
    private TierAllocation computeConfiguredAllocation(
            Integer level, String subLevel, BigDecimal incomeBaseUzs, Currency displayCurrency,
            BigDecimal bankMonthlyUzs, BigDecimal personalLoansRemainingUzs,
            BigDecimal plannedSetAsideUzs, BucketPaid paid, BucketPaid marks, MonthPaid monthPaid,
            List<ActionItem> upcoming) {

        if (level < 2 || level > 6 || subLevel == null) {
            return notDefinedAllocation("page.plan.note.levelRulesUnset",
                    Map.of("level", String.valueOf(level)),
                    "Allocation rules for Level " + level + " will be configured by you once you reach this tier.");
        }
        LevelAllocationRule rule = ruleRepository.findBySubLevel(subLevel).orElse(null);
        if (rule == null) {
            // The debt band is in `text` only: it is English prose the client cannot re-derive, and
            // the tier card already names the band right above this note.
            return notDefinedAllocation("page.plan.note.subLevelRulesUnset",
                    Map.of("subLevel", subLevel),
                    "Allocation for Level " + subLevel + " (" + subLevelDebtLabel(subLevel)
                            + ") isn't set yet — open the rules editor from the tier card to define it.");
        }

        String[] p = {
                toPctStr(rule.getDonationPercent()), toPctStr(rule.getEmergencyPercent()),
                toPctStr(rule.getInvestmentsPercent()), toPctStr(rule.getStocksPercent())
        };
        List<AllocationLine> lines = percentLines(incomeBaseUzs, displayCurrency, paid, marks,
                p[0], p[1], p[2]);

        List<ActionItem> actions = new ArrayList<>();
        if (bankMonthlyUzs.signum() > 0) {
            actions.add(payBank(monthPaid, bankMonthlyUzs));
        }
        if (plannedSetAsideUzs.signum() > 0) {
            actions.add(setAside(plannedSetAsideUzs, displayCurrency, monthPaid));
        }
        if (personalLoansRemainingUzs.signum() > 0) {
            BigDecimal personalTarget = personalLoansRemainingUzs.multiply(PERSONAL_LOAN_PAYDOWN_RATE, MC);
            actions.add(payPersonal(personalTarget, displayCurrency, monthPaid, personalTarget));
        }
        if (rule.getNote() != null && !rule.getNote().isBlank()) {
            actions.add(userNote(rule.getNote()));
        }
        actions.addAll(upcoming);

        return TierAllocation.builder()
                .scenarioKey(subLevel)
                .scenarioLabel("Level " + subLevel + " — " + subLevelDebtLabel(subLevel))
                .lines(lines)
                .actions(actions)
                .allocationLocked(isAllocationLocked(actions))
                .build();
    }

    /**
     * Percentages for a Levels 2–6 (level, sub-level) from configured rules. Level 1 is handled
     * by {@link #computeLevel1Plan} directly (it needs the per-case calc base, not just %), so
     * this is only called for Levels 2–6. The extra params are kept for call-site symmetry.
     */
    private String[] bucketPercents(Integer level, String subLevel,
            BigDecimal stableUzs, BigDecimal mandatoryUzs,
            BigDecimal bankUzs, BigDecimal personalRemUzs) {
        if (level != null && level >= 2 && level <= 6 && subLevel != null) {
            return ruleRepository.findBySubLevel(subLevel)
                    .map(r -> new String[]{
                            toPctStr(r.getDonationPercent()), toPctStr(r.getEmergencyPercent()),
                            toPctStr(r.getInvestmentsPercent()), toPctStr(r.getStocksPercent())})
                    .orElse(new String[]{null, null, null, null});
        }
        return new String[]{null, null, null, null};
    }

    private static String toPctStr(BigDecimal pct) {
        return pct == null ? null : pct.stripTrailingZeros().toPlainString();
    }

    // ── Allocation-rule view + per-level config (Levels 2–6, Level 1 reference) ──

    /** Configured minimum leftover for a level, in UZS. Level 1 defaults to 5M. */
    private BigDecimal minLeftoverUzs(int level) {
        BigDecimal v = levelConfigRepository.findByLevel(level)
                .map(LevelConfig::getMinLeftover).orElse(null);
        if (v != null) return v;
        return FIVE_MILLION_UZS;
    }

    /** The user's current level from stable income − subscriptions (null if income unset). */
    private Integer currentLevel() {
        Settings s = settingsService.getOrCreate();
        if (s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncome().signum() <= 0) return null;
        BigDecimal stableUzs = s.getMonthlyStableIncome();
        return computeLevel(stableUzs.subtract(sumActiveSubscriptionsUzs()));
    }

    /** Inclusive lower / exclusive upper left-money bound (UZS) for a level. */
    private BigDecimal levelIncomeLow(int level) {
        return level <= 1 ? BigDecimal.ZERO : LEVEL_BREAKPOINTS_UZS[level - 2];
    }
    private BigDecimal levelIncomeHigh(int level) {
        return LEVEL_BREAKPOINTS_UZS[level - 1];
    }

    /**
     * Reference percentages for Level 1's three sub-levels (read-only comparison). The ".2"
     * row shows the comfortable Case-B allocation (7/3/10/3); the actual numbers vary by
     * loan/debt composition and the 5M tight/comfortable split (see {@link #computeLevel1Plan}).
     */
    private String[] level1Reference(String subLevel) {
        if (subLevel.endsWith(".1")) return new String[]{"10", "5", "15", "5"};
        if (subLevel.endsWith(".2")) return new String[]{"7", "3", "10", "3"};
        return new String[]{"2", null, null, null}; // .3
    }

    @Transactional(readOnly = true)
    public AllocationRulesViewResponse getAllocationRules() {
        Settings s = settingsService.getOrCreate();
        boolean missingIncome = s.getMonthlyStableIncome() == null
                || s.getMonthlyStableIncome().signum() <= 0;

        Integer curLevel = currentLevel();
        String curSubLevel = null;
        if (curLevel != null && !missingIncome) {
            BigDecimal stableUzs = s.getMonthlyStableIncome();
            LocalDate today = LocalDate.now();
            YearMonth now = YearMonth.now();
            BigDecimal debtTotal = sumBankLoanMonthlyPaymentsUzs(today).add(sumDebt34Uzs(now));
            BigDecimal ratio = stableUzs.signum() > 0 ? debtTotal.divide(stableUzs, MC) : null;
            curSubLevel = computeSubLevel(curLevel, debtTotal, ratio);
        }

        YearMonth thisMonth = YearMonth.now();
        List<LevelView> levels = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            LevelConfig cfg = levelConfigRepository.findByLevel(level).orElse(null);
            LocalDate exp = cfg != null ? cfg.getExpirationMonth() : null;
            boolean locked = exp != null && thisMonth.isBefore(YearMonth.from(exp));
            boolean builtIn = level == 1;
            boolean editable = curLevel != null && curLevel == level && !locked;

            BigDecimal minLeftover = cfg != null && cfg.getMinLeftover() != null
                    ? cfg.getMinLeftover()
                    : (level == 1 ? FIVE_MILLION_UZS : null);

            List<SubLevelView> subs = new ArrayList<>(3);
            for (int sub = 1; sub <= 3; sub++) {
                String subLevel = level + "." + sub;
                String[] p = builtIn ? level1Reference(subLevel)
                        : ruleRepository.findBySubLevel(subLevel)
                            .map(r -> new String[]{toPctStr(r.getDonationPercent()), toPctStr(r.getEmergencyPercent()),
                                    toPctStr(r.getInvestmentsPercent()), toPctStr(r.getStocksPercent())})
                            .orElse(new String[]{null, null, null, null});
                subs.add(SubLevelView.builder()
                        .subLevel(subLevel)
                        .debtLabel(subLevelDebtLabel(subLevel))
                        .donationPercent(parsePct(p[0]))
                        .emergencyPercent(parsePct(p[1]))
                        .investmentsPercent(parsePct(p[2]))
                        .stocksPercent(parsePct(p[3]))
                        .build());
            }

            levels.add(LevelView.builder()
                    .level(level)
                    .incomeLow(levelIncomeLow(level))
                    .incomeHigh(levelIncomeHigh(level))
                    .minLeftover(minLeftover)
                    .expirationMonth(exp == null ? null : YearMonth.from(exp).toString())
                    .locked(locked)
                    .editable(editable)
                    .builtIn(builtIn)
                    .subLevels(subs)
                    .build());
        }

        return AllocationRulesViewResponse.builder()
                .currentLevel(curLevel)
                .currentSubLevel(curSubLevel)
                .missingStableIncome(missingIncome)
                .levels(levels)
                .build();
    }

    /**
     * Save one level's config. Only the user's CURRENT level may be saved, and only when it
     * isn't locked by an unreached expiration month. Level 1's percentages are built-in, so
     * only its minimum leftover / expiration are stored; Levels 2–6 also upsert sub-level
     * percentages (a sub-level with all-null percents is removed).
     */
    @Transactional
    public AllocationRulesViewResponse saveLevelConfig(LevelConfigRequest req) {
        Integer level = req.getLevel();
        if (level == null || level < 1 || level > 6) {
            throw new IllegalArgumentException("Level must be between 1 and 6.");
        }
        Integer cur = currentLevel();
        if (cur == null || !cur.equals(level)) {
            throw new IllegalArgumentException(
                    "You can only edit your current level" + (cur == null ? "." : " (Level " + cur + ")."));
        }
        // Reject when the level is currently locked (existing expiration not yet reached).
        LevelConfig cfg = levelConfigRepository.findByLevel(level).orElse(null);
        LocalDate existingExp = cfg != null ? cfg.getExpirationMonth() : null;
        if (existingExp != null && YearMonth.now().isBefore(YearMonth.from(existingExp))) {
            throw new IllegalArgumentException(
                    "Level " + level + " is locked until " + YearMonth.from(existingExp) + ".");
        }

        if (cfg == null) {
            cfg = new LevelConfig();
            cfg.setLevel(level);
        }
        cfg.setMinLeftover(req.getMinLeftover());
        cfg.setExpirationMonth(req.getExpirationMonth() == null ? null
                : req.getExpirationMonth().withDayOfMonth(1));
        levelConfigRepository.save(cfg);

        // Percentages: Level 1 is built-in; 2–6 upsert each provided sub-level row.
        if (level >= 2 && req.getRules() != null) {
            for (LevelAllocationRuleRequest rule : req.getRules()) {
                if (rule.getSubLevel() == null || rule.getSubLevel().isBlank()) continue;
                if (parseLevel(rule.getSubLevel()) != level) {
                    throw new IllegalArgumentException(
                            "Sub-level " + rule.getSubLevel() + " doesn't belong to Level " + level + ".");
                }
                upsertRule(rule);
            }
        }
        return getAllocationRules();
    }

    /** Upsert one sub-level's percentages; all-null → delete (reverts to "not set"). */
    private void upsertRule(LevelAllocationRuleRequest req) {
        String subLevel = req.getSubLevel();
        int level = parseLevel(subLevel);
        boolean allNull = req.getDonationPercent() == null && req.getEmergencyPercent() == null
                && req.getInvestmentsPercent() == null && req.getStocksPercent() == null;
        LevelAllocationRule existing = ruleRepository.findBySubLevel(subLevel).orElse(null);
        if (allNull) {
            if (existing != null) ruleRepository.delete(existing);
            return;
        }
        LevelAllocationRule r = existing != null ? existing : new LevelAllocationRule();
        r.setLevel(level);
        r.setSubLevel(subLevel);
        r.setDonationPercent(req.getDonationPercent());
        r.setEmergencyPercent(req.getEmergencyPercent());
        r.setInvestmentsPercent(req.getInvestmentsPercent());
        r.setStocksPercent(req.getStocksPercent());
        r.setNote(req.getNote() != null && req.getNote().isBlank() ? null : req.getNote());
        ruleRepository.save(r);
    }

    private static BigDecimal parsePct(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    /** "{level}.{sub}" → level int. */
    private int parseLevel(String subLevel) {
        try {
            return Integer.parseInt(subLevel.split("\\.")[0]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad sub-level format: " + subLevel);
        }
    }

    /**
     * Action item helpers.
     *
     * <p>Every sentence below is composed here, in English, so each carries the translation key
     * and the values to interpolate alongside it: `text` is what an older client prints, `code` +
     * `params` are what a current one renders in the viewer's language. Both are built from the
     * same locals, so the two forms cannot drift apart.
     */
    private static ActionItem info(String code, Map<String, String> params, String text) {
        return ActionItem.builder().text(text).code(code).params(params).build();
    }

    /**
     * The user's OWN note from the allocation-rules editor. It carries no code deliberately: the
     * sentence is theirs, in whichever language they typed it, so there is nothing to translate
     * and a key would print somebody else's words in its place.
     */
    private static ActionItem userNote(String text) {
        return ActionItem.builder().text(text).build();
    }

    /** Map.of rejects null values, and a counterparty name reaches us from user data. */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static ActionItem payBank(MonthPaid monthPaid, BigDecimal target) {
        return ActionItem.builder()
                .text("Pay bank installments this month.")
                .code("page.plan.actionPayBank")
                .params(Map.of())
                .action("PAY_BANK")
                .paid(monthPaid.bankInstallments())
                .target(target)
                .unlockThreshold(bankUnlockThreshold(target))
                .build();
    }

    private static ActionItem payPersonal(BigDecimal targetDisplay, Currency displayCurrency,
                                          MonthPaid monthPaid, BigDecimal target) {
        String amount = formatNumber(targetDisplay) + " " + displayCurrency;
        return ActionItem.builder()
                .text("Pay at least 34% (~ " + amount + ") of personal loans this month.")
                .code("page.plan.actionPayPersonal")
                .params(Map.of("amount", amount))
                .action("PAY_PERSONAL_LOAN")
                .paid(monthPaid.personalLoanRepayments())
                .target(target)
                // Personal loans must reach the full 34% target to unlock.
                .unlockThreshold(target)
                .build();
    }

    /**
     * The repayment plan as its own allocation ask. Same PAY_PERSONAL_LOAN action (it is paid
     * the same way), but worded as the commitment the user made rather than as the 34% rule.
     */
    private static ActionItem setAside(BigDecimal targetDisplay, Currency displayCurrency,
                                       MonthPaid monthPaid) {
        String amount = formatNumber(targetDisplay) + " " + displayCurrency;
        return ActionItem.builder()
                .text("Set aside " + amount + " this month for your loan repayment plan.")
                .code("page.plan.action.setAside")
                .params(Map.of("amount", amount))
                .action("PAY_PERSONAL_LOAN")
                .paid(monthPaid.personalLoanRepayments())
                .target(targetDisplay)
                .unlockThreshold(targetDisplay)
                .build();
    }

    /** Bank installment unlock amount: 90% of the average monthly payment. */
    private static BigDecimal bankUnlockThreshold(BigDecimal target) {
        return target == null ? null : target.multiply(BANK_UNLOCK_RATE, MC);
    }

    /**
     * Whether allocation recording should be locked: true when any actionable item is still
     * below its unlock threshold. Informational items (no action key, no threshold) never lock.
     */
    private static boolean isAllocationLocked(List<ActionItem> actions) {
        return actions.stream().anyMatch(a ->
                a.getAction() != null
                        && a.getUnlockThreshold() != null
                        && nullToZero(a.getPaid()).compareTo(a.getUnlockThreshold()) < 0);
    }

    /**
     * Build the four bucket lines from minimum-percent strings. Pass null for a bucket
     * that should render as "NO NEED" at this tier. Paid-this-month amounts (already in
     * display currency) come from the BucketPaid context.
     */
    /**
     * Stocks are no longer an allocation bucket — the owner does not allocate to stocks at all.
     * The percentage is still computed upstream (it is part of the Level-1 scenario tuple) but
     * nothing is emitted for it, so it cannot appear in the Overview, the ledger, or a month.
     */
    private List<AllocationLine> percentLines(BigDecimal incomeUzs, Currency displayCurrency,
                                              BucketPaid paid, BucketPaid marks,
                                              String donationPct, String emergencyPct,
                                              String investmentsPct) {
        List<AllocationLine> lines = new ArrayList<>(3);
        lines.add(line("DONATION",    "Donation",    donationPct,    incomeUzs, displayCurrency,
                paid.donation(), marks.donation()));
        lines.add(line("EMERGENCY",   "Emergency",   emergencyPct,   incomeUzs, displayCurrency,
                paid.emergency(), marks.emergency()));
        lines.add(line("INVESTMENTS", "Investments", investmentsPct, incomeUzs, displayCurrency,
                paid.investments(), marks.investments()));
        return lines;
    }

    private AllocationLine line(String bucket, String label, String pctStr,
                                BigDecimal incomeUzs, Currency displayCurrency,
                                BigDecimal paidDisplay, BigDecimal markedDisplay) {
        if (pctStr == null) {
            // Not recommended at this tier. Still surface paidAmount so the frontend can
            // tell the user "you paid X here even though it's not recommended this month".
            return AllocationLine.builder()
                    .bucket(bucket).label(label).recommended(false)
                    .minPercent(null).minAmount(null)
                    .paidAmount(paidDisplay).markedAmount(markedDisplay)
                    .paidPercent(null).remainingAmount(null)
                    .build();
        }
        BigDecimal pct = new BigDecimal(pctStr);
        BigDecimal amountUzs = incomeUzs.multiply(pct, MC).divide(new BigDecimal("100"), MC);
        BigDecimal minAmount = amountUzs;

        BigDecimal paidPercent = minAmount.signum() > 0
                ? paidDisplay.multiply(new BigDecimal("100"), MC).divide(minAmount, MC)
                : null;
        BigDecimal remaining = minAmount.subtract(paidDisplay);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;

        return AllocationLine.builder()
                .bucket(bucket).label(label).recommended(true)
                .minPercent(pct)
                .minAmount(minAmount)
                .paidAmount(paidDisplay)
                .markedAmount(markedDisplay)
                .paidPercent(paidPercent)
                .remainingAmount(remaining)
                .build();
    }

    private TierAllocation notDefinedAllocation(String code, Map<String, String> params, String note) {
        return TierAllocation.builder()
                .scenarioKey(null)
                .scenarioLabel("Guidance not yet defined for this tier")
                .lines(List.of())
                .actions(List.of(info(code, params, note)))
                .build();
    }

    /** "June 2026" — for the "allocation tracking starts …" guidance note. */
    private static String monthLabel(YearMonth ym) {
        return ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                + " " + ym.getYear();
    }

    private static String formatNumber(BigDecimal n) {
        if (n == null) return "0";
        // Cheap thousand-grouping. Detail is fine since notes are short scenario hints.
        return n.setScale(0, RoundingMode.HALF_UP).toPlainString()
                .replaceAll("(\\d)(?=(\\d{3})+$)", "$1 "); // thin space
    }
}
